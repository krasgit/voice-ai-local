package local.voice;

import java.util.*;
import java.util.regex.*;

/**
 * Pure text helpers used by the server, extracted so they can be unit-tested
 * without a live WebSocket/LLM. No I/O, no state.
 */
public final class TextUtil {
    private TextUtil() {}

    private static final Pattern SENTENCE =
            Pattern.compile("(.+?[.!?…。！？])(\\s+|$)", Pattern.DOTALL);

    /**
     * Splits buffered streaming text into complete sentences suitable for TTS.
     * Skips lone list markers ("1." / "2)") and (unless force) tiny fragments.
     * Returns the emitted sentences; `remainder[0]` receives the unconsumed tail.
     */
    public static List<String> flushSentences(String buf, boolean force, String[] remainder) {
        List<String> out = new ArrayList<>();
        Matcher m = SENTENCE.matcher(buf);
        int consumed = 0;
        while (m.find()) {
            String s = m.group(1).trim();
            if (s.matches("\\d+[.)]")) break;
            if (!force && s.replaceAll("[^\\p{L}]", "").length() < 3) break;
            if (!s.isEmpty()) out.add(s);
            consumed = m.end();
        }
        String rest = buf.substring(consumed);
        if (force && rest.trim().length() > 0) {
            out.add(rest.trim());
            rest = "";
        }
        if (remainder != null && remainder.length > 0) remainder[0] = rest;
        return out;
    }

    /** Lowercases and strips non-alphanumeric for lenient comparison. */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9']", "").trim();
    }

    /** Splits into comparable lowercase word tokens. */
    public static String[] words(String s) {
        String t = (s == null ? "" : s).toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9'\\s]", "").trim();
        if (t.isEmpty()) return new String[0];
        return t.split("\\s+");
    }

    /**
     * Percentage of target words that appear in the heard text (word-level
     * pronunciation/dictation scoring).
     */
    public static int wordScore(String target, String heard) {
        String[] tgt = words(target);
        Set<String> got = new HashSet<>(Arrays.asList(words(heard)));
        if (tgt.length == 0) return 0;
        int ok = 0;
        for (String w : tgt) if (got.contains(w)) ok++;
        return (int) Math.round(100.0 * ok / tgt.length);
    }

    // Common English words, used to tell real English from romanized Bulgarian.
    private static final Set<String> EN_COMMON = new HashSet<>(Arrays.asList(
        "the","a","an","and","or","but","is","are","was","were","be","been","being","am",
        "have","has","had","do","does","did","will","would","can","could","should","may",
        "i","you","he","she","it","we","they","me","him","her","us","them","my","your",
        "his","its","our","their","this","that","these","those","to","of","in","on","at",
        "for","with","from","by","as","so","if","not","no","yes","hello","hi","how","are",
        "you","doing","today","tomorrow","yesterday","tell","me","something","what","where",
        "when","why","who","which","good","morning","evening","night","thanks","thank","please",
        "like","love","want","need","go","come","see","know","think","say","said","get","got",
        "make","made","time","day","people","work","home","school","food","water","name",
        "please","sorry","okay","ok","because","about","there","here","now","then","very",
        "more","most","some","any","all","one","two","three","would","like","cup","coffee"));

    /** Ratio (0..1) of tokens that are common English words. */
    public static double englishRatio(String text) {
        String[] ws = words(text);
        if (ws.length == 0) return 0.0;
        int hits = 0;
        for (String w : ws) if (EN_COMMON.contains(w)) hits++;
        return (double) hits / ws.length;
    }

    public static boolean hasCyrillic(String s) {
        return s != null && s.chars().anyMatch(c -> c >= 0x0400 && c <= 0x04FF);
    }

    /**
     * Given the Bulgarian- and English-forced transcriptions of the same audio,
     * pick the one that best matches what was actually spoken.
     * - Strong English signal (many real English words) -> English.
     * - Otherwise, if the BG result has Cyrillic -> Bulgarian.
     * - Fallbacks keep whichever is non-empty.
     */
    public static String chooseTranscript(String bg, String en) {
        String b = bg == null ? "" : bg.trim();
        String e = en == null ? "" : en.trim();
        if (b.isEmpty() && e.isEmpty()) return "";
        if (b.isEmpty()) return e;
        if (e.isEmpty()) return b;
        double enR = englishRatio(e);
        if (enR >= 0.4) return e;            // clearly real English
        if (hasCyrillic(b)) return b;        // Bulgarian speech
        return enR > 0 ? e : b;              // weak English beats gibberish Cyrillic
    }
}
