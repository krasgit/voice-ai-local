package local.voice;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogicTest {

    // ---- Dictionary ----
    @Test void dictionaryKnownWords() {
        assertEquals("питам", VoiceServer.Dictionary.lookup("ask"));
        assertEquals("време", VoiceServer.Dictionary.lookup("weather"));
        assertEquals("щастлив", VoiceServer.Dictionary.lookup("happy")); // not Russian "счастлив"
        assertEquals("книга", VoiceServer.Dictionary.lookup("Book"));    // case-insensitive
        assertEquals("питам", VoiceServer.Dictionary.lookup(" ask? "));  // punctuation stripped
    }
    @Test void dictionaryMissReturnsNull() {
        assertNull(VoiceServer.Dictionary.lookup("serendipity"));
        assertNull(VoiceServer.Dictionary.lookup(""));
    }

    // ---- IntentRouter ----
    @Test void routeTeacherMode() {
        assertEquals("ENGLISH_LEARNING", VoiceServer.IntentRouter.route("hi", "teacher").intent);
    }
    @Test void routeEnglishKeyword() {
        assertEquals("ENGLISH_LEARNING", VoiceServer.IntentRouter.route("check my english", "chat").intent);
        assertEquals("ENGLISH_LEARNING", VoiceServer.IntentRouter.route("граматика въпрос", "chat").intent);
    }
    @Test void routeReasoning() {
        var r = VoiceServer.IntentRouter.route("debug this code step by step", "chat");
        assertEquals("REASONING", r.intent);
        assertTrue(r.reasoning);
    }
    @Test void routeGeneralDefault() {
        var r = VoiceServer.IntentRouter.route("как си днес", "chat");
        assertEquals("GENERAL_CHAT", r.intent);
        assertFalse(r.reasoning);
    }

    // ---- TextUtil.flushSentences ----
    @Test void flushEmitsCompleteSentences() {
        String[] rem = new String[1];
        List<String> out = TextUtil.flushSentences("Hello there. How are you? ", false, rem);
        assertEquals(List.of("Hello there.", "How are you?"), out);
        assertEquals("", rem[0].trim());
    }
    @Test void flushBuffersIncomplete() {
        String[] rem = new String[1];
        List<String> out = TextUtil.flushSentences("This is not finished yet", false, rem);
        assertTrue(out.isEmpty());
        assertEquals("This is not finished yet", rem[0]);
    }
    @Test void flushKeepsListMarkerWithClause() {
        String[] rem = new String[1];
        // A lone "1." must not be emitted on its own.
        List<String> out = TextUtil.flushSentences("Tips: 1. Practice daily. ", false, rem);
        assertFalse(out.contains("1."));
    }
    @Test void flushForceFlushesRemainder() {
        String[] rem = new String[1];
        List<String> out = TextUtil.flushSentences("No terminator here", true, rem);
        assertEquals(List.of("No terminator here"), out);
        assertEquals("", rem[0]);
    }

    // ---- TextUtil scoring ----
    @Test void wordScorePerfect() {
        assertEquals(100, TextUtil.wordScore("I like cats", "i like cats"));
    }
    @Test void wordScorePartial() {
        assertEquals(67, TextUtil.wordScore("I like cats", "I like dogs"));
    }
    @Test void wordScoreIgnoresPunctuationAndCase() {
        assertEquals(100, TextUtil.wordScore("Hello, World!", "hello world"));
    }
    @Test void normalizeStrips() {
        assertEquals("goes", TextUtil.normalize("  Goes! "));
        assertEquals("don't", TextUtil.normalize("Don't"));   // apostrophes preserved
    }

    // ---- Language chooser ----
    @Test void chooseRealEnglish() {
        // EN audio: en is real English, bg is romanized gibberish -> pick en.
        String en = "Hello? How are you doing today? Tell me something.";
        String bg = "Здравейте, какво ви е делена си?";
        assertEquals(en, TextUtil.chooseTranscript(bg, en));
    }
    @Test void chooseBulgarian() {
        // BG audio: en is romanized ("Zdravaj kak si dnes"), bg is real Cyrillic.
        String en = "Zdravaj kak si dnes. A zkazi mi nesto.";
        String bg = "Здравей, как си днес?";
        assertEquals(bg, TextUtil.chooseTranscript(bg, en));
    }
    @Test void chooseHandlesEmpty() {
        assertEquals("Здравей", TextUtil.chooseTranscript("Здравей", ""));
        assertEquals("hello", TextUtil.chooseTranscript("", "hello"));
        assertEquals("", TextUtil.chooseTranscript("", ""));
    }
    @Test void englishRatioSignal() {
        assertTrue(TextUtil.englishRatio("Hello how are you today") >= 0.4);
        assertTrue(TextUtil.englishRatio("Zdravaj kak si dnes") < 0.4);
    }
}
