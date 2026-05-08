package interview.guide.modules.knowledgebase.service;

import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.QueryDebugInfo;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class KnowledgeBaseChunkEvidenceMapper {

    static final String METADATA_KB_ID = "kb_id";
    static final String METADATA_KB_ID_LONG = "kb_id_long";
    static final String METADATA_SOURCE_TITLE = "source_title";
    static final String METADATA_SECTION_INDEX = "section_index";
    static final String METADATA_SECTION_TITLE = "section_title";
    static final String METADATA_CHUNK_INDEX = "chunk_index";
    static final String METADATA_PREVIEW = "preview";

    static final String DEFAULT_SECTION_TITLE = "正文";
    static final String OVERVIEW_SECTION_TITLE = "概述";

    private static final int PREVIEW_LIMIT = 180;
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s*(.+?)\\s*$");

    private KnowledgeBaseChunkEvidenceMapper() {
    }

    static List<Document> buildChunkDocuments(Long knowledgeBaseId, KnowledgeBaseEntity knowledgeBase, String content, TextSplitter textSplitter) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<Section> sections = splitSections(content);
        if (sections.isEmpty()) {
            sections = List.of(new Section(1, DEFAULT_SECTION_TITLE, content.trim()));
        }

        String sourceTitle = resolveSourceTitle(knowledgeBase, content);
        List<Document> documents = new ArrayList<>();
        int chunkIndex = 1;

        for (Section section : sections) {
            String sectionText = normalizeText(section.text());
            if (sectionText.isBlank()) {
                continue;
            }

            List<Document> chunks = textSplitter.apply(List.of(new Document(sectionText)));
            if (chunks == null || chunks.isEmpty()) {
                chunks = List.of(new Document(sectionText));
            }

            for (Document chunk : chunks) {
                String chunkText = normalizeText(chunk.getText());
                if (chunkText.isBlank()) {
                    continue;
                }

                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put(METADATA_KB_ID, knowledgeBaseId == null ? null : knowledgeBaseId.toString());
                metadata.put(METADATA_KB_ID_LONG, knowledgeBaseId);
                metadata.put(METADATA_SOURCE_TITLE, sourceTitle);
                metadata.put(METADATA_SECTION_INDEX, section.index());
                metadata.put(METADATA_SECTION_TITLE, section.title());
                metadata.put(METADATA_CHUNK_INDEX, chunkIndex);
                metadata.put(METADATA_PREVIEW, preview(chunkText));

                documents.add(new Document(chunkText, metadata));
                chunkIndex++;
            }
        }

        return documents;
    }

    static QueryDebugInfo.Hit toDebugHit(Document doc) {
        if (doc == null) {
            return new QueryDebugInfo.Hit(null, null, null, null, null);
        }

        Map<String, Object> metadata = doc.getMetadata();
        String knowledgeBaseId = readString(metadata, METADATA_KB_ID);
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            knowledgeBaseId = readString(metadata, METADATA_KB_ID_LONG);
        }

        String sourceTitle = readString(metadata, METADATA_SOURCE_TITLE);
        String sectionTitle = readString(metadata, METADATA_SECTION_TITLE);
        Integer chunkIndex = readInteger(metadata, METADATA_CHUNK_INDEX);
        String preview = readString(metadata, METADATA_PREVIEW);
        if (preview == null || preview.isBlank()) {
            preview = preview(doc.getText());
        }

        return new QueryDebugInfo.Hit(knowledgeBaseId, sourceTitle, sectionTitle, chunkIndex, preview);
    }

    private static List<Section> splitSections(String content) {
        String[] lines = content.split("\\R", -1);
        boolean hasHeading = Arrays.stream(lines)
            .map(String::trim)
            .anyMatch(line -> HEADING_PATTERN.matcher(line).matches());
        if (!hasHeading) {
            return List.of(new Section(1, DEFAULT_SECTION_TITLE, content.trim()));
        }

        List<Section> sections = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentTitle = null;
        boolean sourceHeadingCaptured = false;
        int sectionIndex = 1;

        for (String rawLine : lines) {
            String trimmedLine = rawLine.trim();
            Matcher matcher = HEADING_PATTERN.matcher(trimmedLine);
            if (matcher.matches()) {
                int headingLevel = matcher.group(1).length();
                String heading = matcher.group(2).trim();

                if (!sourceHeadingCaptured && headingLevel == 1) {
                    sourceHeadingCaptured = true;
                    currentTitle = OVERVIEW_SECTION_TITLE;
                    continue;
                }

                if (headingLevel >= 2 || (headingLevel == 1 && sourceHeadingCaptured)) {
                    if (body.length() > 0) {
                        sections.add(new Section(sectionIndex++, currentTitle == null ? DEFAULT_SECTION_TITLE : currentTitle, body.toString().trim()));
                        body.setLength(0);
                    }
                    currentTitle = heading;
                    continue;
                }
            }

            if (currentTitle == null) {
                currentTitle = sourceHeadingCaptured ? OVERVIEW_SECTION_TITLE : DEFAULT_SECTION_TITLE;
            }

            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(rawLine);
        }

        if (body.length() > 0) {
            sections.add(new Section(sectionIndex, currentTitle == null ? DEFAULT_SECTION_TITLE : currentTitle, body.toString().trim()));
        }

        return sections;
    }

    private static String resolveSourceTitle(KnowledgeBaseEntity knowledgeBase, String content) {
        if (knowledgeBase != null && knowledgeBase.getName() != null && !knowledgeBase.getName().isBlank()) {
            return knowledgeBase.getName().trim();
        }
        if (knowledgeBase != null && knowledgeBase.getOriginalFilename() != null && !knowledgeBase.getOriginalFilename().isBlank()) {
            return stripExtension(knowledgeBase.getOriginalFilename().trim());
        }

        if (content != null && !content.isBlank()) {
            for (String rawLine : content.split("\\R", -1)) {
                String trimmedLine = rawLine.trim();
                Matcher matcher = HEADING_PATTERN.matcher(trimmedLine);
                if (matcher.matches() && matcher.group(1).length() == 1) {
                    return matcher.group(2).trim();
                }
            }

            for (String rawLine : content.split("\\R", -1)) {
                String trimmedLine = rawLine.trim();
                if (!trimmedLine.isBlank()) {
                    return trimmedLine.length() > 120 ? trimmedLine.substring(0, 120) : trimmedLine;
                }
            }
        }

        return DEFAULT_SECTION_TITLE;
    }

    private static String preview(String text) {
        String normalized = normalizeText(text);
        if (normalized.length() <= PREVIEW_LIMIT) {
            return normalized;
        }
        return normalized.substring(0, PREVIEW_LIMIT) + "...";
    }

    private static String normalizeText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String stripExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0) {
            return filename;
        }
        return filename.substring(0, lastDot);
    }

    private static String readString(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer readInteger(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record Section(int index, String title, String text) {
    }
}
