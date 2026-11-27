package io.github.gmshield.artifacts.service;

import io.github.gmshield.conversations.service.TurnReference;
import io.github.gmshield.knowledge.service.CoreRecordTypes;
import io.github.gmshield.knowledge.service.Ontology;
import io.github.gmshield.library.service.Language;
import io.github.gmshield.library.service.Library;
import io.github.gmshield.shared.service.ApiException;
import io.github.gmshield.shared.service.ApiProblem;
import io.github.gmshield.shared.service.OwnerContext;
import java.nio.charset.StandardCharsets;
import java.text.BreakIterator;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Exports of the owner's artifacts (Story 5.7 part A, FR-39): one or several artifacts of a campaign as one PDF, TXT
 * or Markdown file, or as JPG images (one artifact one {@code .jpg}, several a {@code .zip} of them), built in memory
 * and answered as bytes; nothing is written to disk and no outside service is called.
 *
 * <p>Each id is read exactly as {@link Artifacts#get(UUID, UUID)} reads it: the campaign's own artifacts and the live
 * artifacts shared into it, at their newest version. One id that is not readable (unknown, another owner's, another
 * campaign's, deleting) makes the whole export {@code request.not_found} (404), naming none, and nothing is rendered.
 *
 * <p>Each artifact prints its name, its record type's name (a core type's in the export's language, an approved type's
 * as the system names it), its fields in the order written, its content, then its sources as numbered lines: a
 * material's place (material, section path, page), a record's type and name, an artifact's type, name and version; a
 * removed source is marked so. The export quotes nothing beyond the artifact's own text. The words around it follow
 * the {@link Language} ({@link ExportLabels}).
 *
 * <p>Limits (Dalton, 2026-11-27): a PDF or JPG export holds at most {@value ExportPdf.Pages#MAX} PDF pages in all, else
 * {@code artifact.export_too_large} (400), refused as the page past the last is reached, before the rest is built; at
 * most {@value ExportGate#MAX} PDF or JPG exports render at once, and a third is refused at once with
 * {@code artifact.export_busy} (503). TXT and Markdown are neither counted nor gated.
 *
 * <p>The log lines {@code artifacts.exported} and {@code artifacts.export_refused} (its reason) carry ids, the count,
 * the format and the size: never a name or text.
 */
@Service
public class ArtifactExports {

    /** The most artifacts one export takes. */
    public static final int MAX_IDS = 50;
    /** A file name's base: at most this many code points. */
    static final int MAX_NAME_BYTES = 200;
    private static final Pattern UNSAFE = Pattern.compile("[\\\\/:*?\"<>|\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** Windows' reserved device names, lower case: a file of one of them, whatever its extension, cannot be saved. */
    private static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "conin$", "conout$", "com0",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9", "com\u00B9", "com\u00B2",
            "com\u00B3", "lpt0", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9", "lpt\u00B9",
            "lpt\u00B2", "lpt\u00B3");
    private static final Logger log = LoggerFactory.getLogger(ArtifactExports.class);

    /** An export of PDF or JPG pages past {@value ExportPdf.Pages#MAX} in all (Story 5.7, Dalton's limit). */
    public static final ApiProblem EXPORT_TOO_LARGE = ApiProblem.of(400, "artifact", "export_too_large",
            "Export too large");
    /** A PDF or JPG export while {@value ExportGate#MAX} others render: refused at once, to try again. */
    public static final ApiProblem EXPORT_BUSY = ApiProblem.of(503, "artifact", "export_busy", "Export busy");

    private final Artifacts artifacts;
    private final Library library;
    private final Ontology ontology;
    private final ExportGate gate;

    @Autowired
    ArtifactExports(Artifacts artifacts, Library library, Ontology ontology) {
        this(artifacts, library, ontology, new ExportGate());
    }

    ArtifactExports(Artifacts artifacts, Library library, Ontology ontology, ExportGate gate) {
        this.artifacts = artifacts;
        this.library = library;
        this.ontology = ontology;
        this.gate = gate;
    }

    /** An export's format, by its query value. */
    public enum Format {
        PDF("pdf", "pdf", "application/pdf"),
        TXT("txt", "txt", "text/plain;charset=UTF-8"),
        MARKDOWN("markdown", "md", "text/markdown;charset=UTF-8"),
        JPG("jpg", "jpg", "image/jpeg");

        private final String tag;
        private final String extension;
        private final String mediaType;

        Format(String tag, String extension, String mediaType) {
            this.tag = tag;
            this.extension = extension;
            this.mediaType = mediaType;
        }

        public String tag() {
            return tag;
        }

        /** The format with exactly this query value, if there is one. */
        public static Optional<Format> of(String tag) {
            return Arrays.stream(values()).filter(format -> format.tag.equals(tag)).findFirst();
        }
    }

    /**
     * An export's file: its name (safe, with its extension), its media type and its bytes. {@code toString} names the
     * media type and the size only: the name is the artifact's.
     */
    public record Rendered(String fileName, String mediaType, byte[] bytes) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Rendered rendered && fileName.equals(rendered.fileName)
                    && mediaType.equals(rendered.mediaType) && Arrays.equals(bytes, rendered.bytes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(fileName, mediaType) * 31 + Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            return "Rendered[mediaType=" + mediaType + ", bytes=" + bytes.length + "]";
        }
    }

    /**
     * The owner's artifacts {@code ids} of the campaign {@code campaignId} (1 to {@value #MAX_IDS}, distinct, in the
     * order given) as one file of this format, with the labels of this language; {@code request.not_found} (404) when
     * any of them is not readable there, as the class says.
     */
    public Rendered export(UUID campaignId, List<UUID> ids, Format format, Language language) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(language, "language");
        if (ids == null || ids.isEmpty() || ids.size() > MAX_IDS || ids.stream().anyMatch(Objects::isNull)
                || new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("artifacts.export_ids_invalid");
        }
        UUID owner = OwnerContext.require();
        boolean paged = format == Format.PDF || format == Format.JPG;
        if (paged && !gate.tryEnter()) {
            refused(owner, campaignId, ids.size(), format, EXPORT_BUSY);
            throw new ApiException(EXPORT_BUSY);
        }
        try {
            return exported(owner, campaignId, ids, format, language);
        } catch (ApiException e) {
            if (e.problem().equals(EXPORT_TOO_LARGE)) {
                refused(owner, campaignId, ids.size(), format, EXPORT_TOO_LARGE);
            }
            throw e;
        } finally {
            if (paged) {
                gate.leave();
            }
        }
    }

    private Rendered exported(UUID owner, UUID campaignId, List<UUID> ids, Format format, Language language) {
        List<Artifacts.Artifact> found = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Optional<Artifacts.Artifact> artifact = artifacts.get(campaignId, id);
            if (artifact.isEmpty()) {
                log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                        .addKeyValue("count", ids.size()).addKeyValue("format", format.tag())
                        .log("artifacts.export_not_found");
                throw new ApiException(ApiProblem.forStatus(404));
            }
            found.add(artifact.get());
        }
        ExportLabels labels = ExportLabels.of(language);
        Map<String, String> types = typeNames(campaignId, language);
        List<ExportSheet> sheets = found.stream().map(artifact -> sheet(artifact, types, labels)).toList();
        Rendered rendered = rendered(sheets, format, labels);
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId)
                .addKeyValue("count", ids.size()).addKeyValue("format", format.tag())
                .addKeyValue("language", language.tag()).addKeyValue("bytes", rendered.bytes().length)
                .log("artifacts.exported");
        return rendered;
    }

    private static void refused(UUID owner, UUID campaignId, int count, Format format, ApiProblem problem) {
        log.atInfo().addKeyValue("ownerId", owner).addKeyValue("campaignId", campaignId).addKeyValue("count", count)
                .addKeyValue("format", format.tag()).addKeyValue("reason", problem.code())
                .log("artifacts.export_refused");
    }

    private static Rendered rendered(List<ExportSheet> sheets, Format format, ExportLabels labels) {
        ExportPdf.Pages pages = new ExportPdf.Pages();
        String base = sheets.size() == 1 ? safeName(sheets.getFirst().name(), labels.one()) : labels.several();
        if (format == Format.JPG && sheets.size() > 1) {
            return new Rendered(base + ".zip", "application/zip", ExportJpg.zip(entries(sheets, labels, pages)));
        }
        byte[] bytes = switch (format) {
            case PDF -> ExportPdf.render(sheets, labels, pages);
            case TXT -> ExportText.txt(sheets, labels);
            case MARKDOWN -> ExportText.markdown(sheets, labels);
            case JPG -> ExportJpg.render(sheets.getFirst(), labels, pages);
        };
        return new Rendered(base + "." + format.extension, format.mediaType, bytes);
    }

    /** Each artifact's image as a zip entry {@code 01-<name>.jpg}, {@code 02-...}, in the order given. */
    private static List<ExportJpg.Entry> entries(List<ExportSheet> sheets, ExportLabels labels,
                                                 ExportPdf.Pages pages) {
        List<ExportJpg.Entry> entries = new ArrayList<>(sheets.size());
        for (int i = 0; i < sheets.size(); i++) {
            ExportSheet sheet = sheets.get(i);
            entries.add(new ExportJpg.Entry(String.format(Locale.ROOT, "%02d-%s.jpg", i + 1,
                    safeName(sheet.name(), labels.one())), ExportJpg.render(sheet, labels, pages)));
        }
        return entries;
    }

    /** The artifact as an export prints it, its types named by {@code types} (a key's own text when unknown). */
    static ExportSheet sheet(Artifacts.Artifact artifact, Map<String, String> types, ExportLabels labels) {
        List<ExportSheet.Source> sources = artifact.references().stream()
                .map(reference -> new ExportSheet.Source(reference.number(), source(reference, types, labels)))
                .toList();
        return new ExportSheet(artifact.name(), types.getOrDefault(artifact.recordType(), artifact.recordType()),
                artifact.fields(), artifact.content(), sources);
    }

    /**
     * A reference's line without its number: a material's place (material, section path, page), a record's type and
     * name, an artifact's type, name and version; {@code (fonte removida)} after a removed one.
     */
    static String source(TurnReference reference, Map<String, String> types, ExportLabels labels) {
        TurnReference.Cited cited = reference.record();
        String text;
        if (cited == null) {
            List<String> parts = new ArrayList<>();
            if (reference.materialName() != null && !reference.materialName().isBlank()) {
                parts.add(reference.materialName());
            }
            if (!reference.sectionPath().isEmpty()) {
                parts.add(String.join(" > ", reference.sectionPath()));
            }
            if (reference.page() > 0) {
                parts.add(labels.page() + " " + reference.page());
            }
            text = String.join(", ", parts);
        } else {
            text = types.getOrDefault(cited.type(), cited.type()) + ": " + cited.name();
            if (TurnReference.ARTIFACT.equals(reference.kind())) {
                text += " (" + labels.version() + " " + cited.artifactVersion() + ")";
            }
        }
        return reference.removed() ? text + " (" + labels.removed() + ")" : text;
    }

    /**
     * The names of the record types of the campaign's system by key, as the interface shows them: a core type's in
     * {@code language}, an approved type's as stored. Empty when the campaign is not readable any more.
     */
    private Map<String, String> typeNames(UUID campaignId, Language language) {
        Map<String, String> names = new HashMap<>();
        Optional<UUID> system = library.campaign(campaignId).map(campaign -> campaign.system().id());
        if (system.isEmpty()) {
            return names;
        }
        boolean portuguese = language == Language.PT_BR;
        int offset = 0;
        while (offset <= Ontology.MAX_OFFSET) {
            Optional<Ontology.RecordTypePage> page = ontology.recordTypes(system.get(), offset, Ontology.MAX_LIMIT);
            if (page.isEmpty() || page.get().items().isEmpty()) {
                break;
            }
            for (Ontology.RecordType type : page.get().items()) {
                String shown = type.core() == null ? type.name()
                        : CoreRecordTypes.shownName(type.core(), portuguese).orElse(type.name());
                names.put(type.key(), shown);
            }
            offset += page.get().items().size();
            if (offset >= page.get().total()) {
                break;
            }
        }
        // A core key the system somehow lacks is still named.
        for (String key : CoreRecordTypes.KEYS) {
            names.computeIfAbsent(key, core -> CoreRecordTypes.shownName(core, portuguese).orElse(core));
        }
        return names;
    }

    /**
     * A file name's base from an artifact's name: characters a file system or a header refuses ({@code \ / : * ? " < >
     * |}, control and format characters) as spaces, spaces collapsed, no leading or trailing dot or space, at most
     * {@value #MAX_NAME_BYTES} UTF-8 bytes cut after a whole character (a zip entry's {@code 01-} and {@code .jpg} still
     * fit a file system's 255), never a reserved device name, whatever its extension or the spaces before it;
     * {@code fallback} when nothing is left.
     */
    static String safeName(String name, String fallback) {
        String safe = UNSAFE.matcher(Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFC))
                .replaceAll(" ");
        safe = SPACES.matcher(safe).replaceAll(" ").strip();
        safe = cut(safe);
        safe = stripDotsAndSpaces(safe);
        if (safe.isEmpty()) {
            return fallback;
        }
        String stem = safe.contains(".") ? safe.substring(0, safe.indexOf('.')) : safe;
        return RESERVED.contains(stem.strip().toLowerCase(Locale.ROOT)) ? "_" + safe : safe;
    }

    /** The text cut to at most {@value #MAX_NAME_BYTES} UTF-8 bytes, at the end of a whole character (grapheme). */
    private static String cut(String text) {
        BreakIterator characters = BreakIterator.getCharacterInstance(Locale.ROOT);
        characters.setText(text);
        int end = 0;
        int bytes = 0;
        for (int next = characters.next(); next != BreakIterator.DONE; next = characters.next()) {
            bytes += text.substring(end, next).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_NAME_BYTES) {
                break;
            }
            end = next;
        }
        return text.substring(0, end);
    }

    private static String stripDotsAndSpaces(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && (text.charAt(start) == '.' || text.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (text.charAt(end - 1) == '.' || text.charAt(end - 1) == ' ')) {
            end--;
        }
        return text.substring(start, end);
    }
}
