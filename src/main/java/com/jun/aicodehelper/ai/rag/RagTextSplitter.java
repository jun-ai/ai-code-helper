package com.jun.aicodehelper.ai.rag;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 标题感知切分：先按 # ## ### 标题分节，节内再按段落切分，
 * 每个切片带「文件名 | 标题」前缀，召回时归属天然清晰。
 * 启动期 RagConfig 和运行时 RagIngestService 共用，避免双份逻辑走样。
 */
public class RagTextSplitter {

    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$");

    private final DocumentByParagraphSplitter paragraphSplitter;

    public RagTextSplitter() {
        this(1000);
    }

    /** maxSegmentSize 可配（small-to-big 的父块粒度） */
    public RagTextSplitter(int maxSegmentSize) {
        this.paragraphSplitter = new DocumentByParagraphSplitter(maxSegmentSize, maxSegmentSize / 5);
    }

    public List<TextSegment> split(Document document) {
        String fileName = document.metadata().getString("file_name");
        List<TextSegment> result = new ArrayList<>();
        for (Section section : splitByHeadings(document.text())) {
            if (section.body().isBlank()) {
                continue;
            }
            String prefix = buildPrefix(fileName, section.heading());
            for (TextSegment seg : paragraphSplitter.split(Document.from(section.body(), document.metadata()))) {
                result.add(TextSegment.from(prefix + "\n" + seg.text(), seg.metadata()));
            }
        }
        return result;
    }

    private String buildPrefix(String fileName, String heading) {
        if ((fileName == null || fileName.isEmpty()) && heading.isEmpty()) {
            return "";
        }
        if (heading.isEmpty()) {
            return fileName;
        }
        if (fileName == null || fileName.isEmpty()) {
            return heading;
        }
        return fileName + " | " + heading;
    }

    private List<Section> splitByHeadings(String text) {
        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentHeading = "";
        for (String line : text.split("\n", -1)) {
            Matcher m = HEADING_PATTERN.matcher(line);
            if (m.find()) {
                if (!body.isEmpty()) {
                    sections.add(new Section(currentHeading, body.toString()));
                    body.setLength(0);
                }
                currentHeading = m.group(2).trim();
                continue;
            }
            body.append(line).append('\n');
        }
        if (!body.isEmpty()) {
            sections.add(new Section(currentHeading, body.toString()));
        }
        return sections;
    }

    private record Section(String heading, String body) {
    }
}
