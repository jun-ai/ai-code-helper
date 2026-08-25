package com.jun.aicodehelper.ai.rag;

import com.jun.aicodehelper.ai.multimodal.ImageCaptionService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 单文件入 RAG：上传成功的 PDF/Word/TXT/MD 调本服务，分片+embedding+写入 Milvus，
 * 后续问题即可被检索到。
 */
@Slf4j
@Service
public class RagIngestService {

    private final RagTextSplitter textSplitter = new RagTextSplitter();

    @Resource
    private EmbeddingStore<TextSegment> embeddingStore;

    @Resource
    private EmbeddingModel minimaxEmbeddingModel;

    @Resource
    private Bm25Index bm25Index;

    @Resource
    private RagProperties ragProperties;

    @Resource
    private ImageCaptionService imageCaptionService;

    @Resource
    private ChatModel zhipuVisionChatModel;

    private static final int PDF_OCR_MAX_PAGES = 8;

    /**
     * 读取文件 → 提取文本 → 切段 → embedding → 写入 Milvus
     * @param tempFile 已落盘的临时文件
     * @param originalName 原始文件名（用作 file_name 元数据，便于来源标注）
     * @return 入库切片数
     */
    public int ingestFile(Path tempFile, String originalName) {
        try {
            String text = extractText(tempFile, originalName);
            if (text == null || text.isBlank()) {
                log.warn("文件无可提取文本，跳过入库: {}", originalName);
                return 0;
            }
            Document document = Document.from(text);
            document.metadata().put("file_name", originalName);
            // 复用启动期 RagConfig 同一份切分器，上传和启动入库行为一致
            List<TextSegment> segments = textSplitter.split(document);
            List<Embedding> embeddings = minimaxEmbeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);
            // BM25 同步追加：上传新文档后立即可被关键词召回
            if (ragProperties.isBm25Enabled()) {
                for (TextSegment seg : segments) {
                    bm25Index.add(seg);
                }
            }
            log.info("RAG 入库: file={} 切片={}", originalName, segments.size());
            return segments.size();
        } catch (Exception e) {
            throw new RuntimeException("文件入库失败: " + originalName + " - " + e.getMessage(), e);
        }
    }

    /**
     * 公开的纯文本抽取：上传后 summary 复用，避免重复 IO。
     * 扫描版 PDF 自动 fallback GLM-4V OCR。
     */
    public String extractText(Path tempFile, String originalName) throws IOException {
        String lower = originalName.toLowerCase();
        if (lower.endsWith(".pdf")) {
            return extractPdf(tempFile);
        } else if (lower.endsWith(".docx")) {
            return extractDocx(tempFile);
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return Files.readString(tempFile);
        }
        throw new IllegalArgumentException("不支持的文件类型: " + originalName);
    }

    /**
     * 图片入库：调视觉模型生成 caption，把 caption 当作文本写进 RAG 库。
     * metadata 含 type=image + image_path，前端可识别渲染缩略图。
     *
     * @return 入库切片数（一般 1）
     */
    public int ingestImage(byte[] imageBytes, String mimeType, String originalName, String savedAs) {
        String caption = imageCaptionService.caption(imageBytes, mimeType);
        String body = (caption != null && !caption.isBlank())
                ? "图片描述：" + caption
                : "图片（" + originalName + "）";
        Document document = Document.from(body);
        document.metadata().put("file_name", originalName);
        document.metadata().put("type", "image");
        document.metadata().put("image_path", savedAs);
        List<TextSegment> segments = textSplitter.split(document);
        if (segments.isEmpty()) {
            segments = List.of(TextSegment.from(body));
        }
        List<Embedding> embeddings = minimaxEmbeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        if (ragProperties.isBm25Enabled()) {
            for (TextSegment seg : segments) {
                bm25Index.add(seg);
            }
        }
        log.info("RAG 图片入库: file={} caption-len={} 切片={}", originalName, body.length(), segments.size());
        return segments.size();
    }

    private String extractPdf(Path path) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(path.toFile())) {
            String text = new PDFTextStripper().getText(pdf);
            // 扫描版 PDF：PDFBox 抽不到文本，回退 GLM-4V 多页 OCR
            if (text == null || text.isBlank()) {
                log.info("PDF 文本为空，转 OCR: {}", path.getFileName());
                return ocrPdfPages(pdf);
            }
            return text;
        }
    }

    /**
     * 扫描版 PDF：把每页渲染成 PNG，调 GLM-4V 视觉模型 OCR。
     * 限前 N 页避免 token 爆炸 + 超时。
     */
    private String ocrPdfPages(PDDocument pdf) throws IOException {
        PDFRenderer renderer = new PDFRenderer(pdf);
        int total = pdf.getNumberOfPages();
        int pages = Math.min(total, PDF_OCR_MAX_PAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            try {
                BufferedImage img = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                byte[] pngBytes = baos.toByteArray();
                String base64 = Base64.getEncoder().encodeToString(pngBytes);
                var resp = zhipuVisionChatModel.chat(ChatRequest.builder()
                        .messages(List.of(UserMessage.from(List.of(
                                TextContent.from("请把图中的文字内容完整 OCR 出来，仅输出文本，不要其他说明。"),
                                ImageContent.from(base64, "image/png")
                        ))))
                        .build());
                String pageText = resp.aiMessage() == null ? null : resp.aiMessage().text();
                if (pageText != null && !pageText.isBlank()) {
                    sb.append(pageText.trim()).append('\n');
                }
            } catch (Exception e) {
                log.warn("PDF 第 {} 页 OCR 失败: {}", i + 1, e.getMessage());
            }
        }
        return sb.toString();
    }

    private String extractDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            return sb.toString();
        }
    }
}
