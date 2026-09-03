package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small-to-big 切分：RagTextSplitter 产出的块作为「父块」（约 1000 字），
 * 父块内再切「子块」（约 300 字）供向量/BM25 检索。
 * 子块 metadata 自包含 parent_id + parent_text（父块全文），检索侧直接读 metadata 回送父块，
 * 无需内存态存储，重启/多进程不丢。parent-chunk-enabled=false 时退化为 RagTextSplitter 现状。
 */
@Slf4j
@Component
public class HierarchicalSplitter {

    public static final String META_PARENT_ID = "parent_id";
    public static final String META_PARENT_TEXT = "parent_text";

    private final RagTextSplitter parentSplitter = new RagTextSplitter();
    private final RagProperties props;

    public HierarchicalSplitter(RagProperties props) {
        this.props = props;
    }

    public List<TextSegment> split(Document document) {
        List<TextSegment> parents = parentSplitter.split(document);
        if (!props.isParentChunkEnabled()) {
            return parents;
        }
        DocumentByParagraphSplitter childSplitter = new DocumentByParagraphSplitter(
                props.getChildMaxChars(), Math.max(50, props.getChildMaxChars() / 5));
        List<TextSegment> children = new ArrayList<>();
        for (TextSegment parent : parents) {
            String parentId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String text = parent.text();
            // 子块正文去掉前缀行单独切，切完再把「文件名 | 标题」前缀拼回去，来源信息不丢
            String prefix = "";
            int nl = text.indexOf('\n');
            if (nl > 0 && text.substring(0, nl).contains(" | ")) {
                prefix = text.substring(0, nl);
                text = text.substring(nl + 1);
            }
            List<TextSegment> parts = childSplitter.split(Document.from(text, document.metadata()));
            if (parts.isEmpty()) {
                // 父块全是空白段落等极端情况：整父块作为单子块
                parts = List.of(TextSegment.from(text));
            }
            for (TextSegment part : parts) {
                children.add(TextSegment.from(prefix + "\n" + part.text(),
                        childMetadata(part, parent, parentId)));
            }
        }
        log.debug("SmallToBig 切分: file={} 父块={} 子块={}",
                document.metadata().getString("file_name"), parents.size(), children.size());
        return children;
    }

    private dev.langchain4j.data.document.Metadata childMetadata(TextSegment part, TextSegment parent, String parentId) {
        dev.langchain4j.data.document.Metadata meta = new dev.langchain4j.data.document.Metadata();
        part.metadata().toMap().forEach((k, v) -> meta.put(k, String.valueOf(v)));
        parent.metadata().toMap().forEach((k, v) -> {
            if (meta.getString(k) == null) meta.put(k, String.valueOf(v));
        });
        meta.put(META_PARENT_ID, parentId);
        meta.put(META_PARENT_TEXT, parent.text());
        return meta;
    }
}
