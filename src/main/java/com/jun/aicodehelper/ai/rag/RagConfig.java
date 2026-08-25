package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.param.MetricType;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * RAG 配置：按标题分节切割带前缀、向量存 Milvus、混合检索 + 缓存。
 * 文档有变更时清空 Milvus collection 重启重建。
 */
@Configuration
@Slf4j
public class RagConfig {

    /**
     * embo-01 输出向量维度
     */
    private static final int EMBEDDING_DIMENSION = 1536;

    private final RagTextSplitter textSplitter = new RagTextSplitter();

    @Resource
    private EmbeddingModel minimaxEmbeddingModel;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private Bm25Index bm25Index;

    @Value("${milvus.host}")
    private String milvusHost;

    @Value("${milvus.port}")
    private Integer milvusPort;

    @Value("${milvus.collection-name}")
    private String collectionName;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        EmbeddingStore<TextSegment> store = MilvusEmbeddingStore.builder()
                .host(milvusHost)
                .port(milvusPort)
                .collectionName(collectionName)
                .dimension(EMBEDDING_DIMENSION)
                .metricType(MetricType.COSINE)
                .autoFlushOnInsert(true)
                .build();
        if (isStoreEmpty(store)) {
            try {
                ingest(store);
            } catch (Throwable e) {
                // 启动期入库失败不阻塞服务上线：向量库可后续手工补，BM25 同步也走空流程
                log.error("RAG 启动期入库失败，跳过构建继续启动: err={}", e.getMessage(), e);
            }
        } else {
            log.info("RAG 向量库命中 Milvus 已有数据，跳过构建");
        }
        return store;
    }

    private boolean isStoreEmpty(EmbeddingStore<TextSegment> store) {
        try {
            Embedding probe = minimaxEmbeddingModel.embed("初始化探测").content();
            return store.search(EmbeddingSearchRequest.builder()
                            .queryEmbedding(probe)
                            .maxResults(1)
                            .build())
                    .matches()
                    .isEmpty();
        } catch (Throwable e) {
            // MiniMax API 暂时挂 / collection 不存在等情况统统视为空库，启动流程不阻塞
            log.warn("RAG 空库探测失败，按空库处理走入库流程: err={}", e.getMessage());
            return true;
        }
    }

    private void ingest(EmbeddingStore<TextSegment> store) {
        List<Document> documents = loadAllDocuments(Paths.get("src/main/resources/docs"));
        List<TextSegment> segments = new ArrayList<>();
        for (Document document : documents) {
            segments.addAll(textSplitter.split(document));
        }
        List<Embedding> embeddings = minimaxEmbeddingModel.embedAll(segments).content();
        store.addAll(embeddings, segments);
        // BM25 同步构建：与 Milvus 共用同一份 segments，保持双路召回一致
        if (ragProperties.isBm25Enabled()) {
            bm25Index.rebuild(segments);
        }
        log.info("RAG 向量库构建完成: {} 篇文档, {} 个切片, 已写入 Milvus", documents.size(), segments.size());
    }

    /**
     * 递归扫描目录，按文件扩展名走对应 loader：
     * .pdf → PDFBox；.docx/.doc → Apache POI；其他交给 FileSystemDocumentLoader（md/txt/html）。
     */
    private List<Document> loadAllDocuments(Path dir) {
        List<Document> docs = new ArrayList<>();
        if (!Files.exists(dir)) {
            return docs;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase();
                try {
                    if (lower.endsWith(".pdf")) {
                        docs.addAll(loadPdf(p));
                    } else if (lower.endsWith(".docx")) {
                        docs.addAll(loadDocx(p));
                    } else if (lower.endsWith(".md") || lower.endsWith(".txt") || lower.endsWith(".html")) {
                        docs.addAll(loadDefaultWithName(p, name));
                    } else {
                        log.debug("跳过不支持的文档格式: {}", name);
                    }
                } catch (Exception e) {
                    log.warn("加载文档失败，跳过: {} err={}", name, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.error("扫描文档目录失败: {}", dir, e);
        }
        return docs;
    }

    private List<Document> loadDefaultWithName(Path path, String fileName) {
        // 直接读文本：FileSystemDocumentLoader 在某些环境下把文件路径当目录处理会抛 is not a directory，
        // 自己读更稳；保留 metadata.file_name 让来源标注准确。
        try {
            String text = Files.readString(path);
            Document doc = Document.from(text);
            doc.metadata().put("file_name", fileName);
            return List.of(doc);
        } catch (IOException e) {
            throw new RuntimeException("读取文本失败: " + fileName, e);
        }
    }

    private List<Document> loadPdf(Path path) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            Document doc = Document.from(text);
            doc.metadata().put("file_name", path.getFileName().toString());
            return List.of(doc);
        }
    }

    private List<Document> loadDocx(Path path) throws Exception {
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            Document d = Document.from(sb.toString());
            d.metadata().put("file_name", path.getFileName().toString());
            return List.of(d);
        }
    }

    @Bean
    public ContentRetriever contentRetriever(ChatModel zhipuChatModel,
                                              com.jun.aicodehelper.ai.metrics.AppMetrics metrics) {
        // 外层 FallbackContentRetriever：Milvus / Embedding 异常时返回空上下文，让 LLM 继续作答
        return new FallbackContentRetriever(
                new CachingContentRetriever(
                        new HybridContentRetriever(embeddingStore(), minimaxEmbeddingModel,
                                bm25Index, zhipuChatModel, ragProperties, metrics)));
    }
}
