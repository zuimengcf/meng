/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart;

import androidx.annotation.Keep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-process Java facade for {@link SmartInputControl}; no context or token is required. */
@Keep
public final class SmartInputApi {
    private SmartInputApi() {
    }

    @Keep
    public interface Callback<T> {
        void onSuccess(T value);
        void onError(Throwable error);
    }

    /** Immutable candidate row accepted by the in-process API. */
    @Keep
    public static final class Candidate {
        private final String key;
        private final String text;
        private final int weight;

        public Candidate(String key, String text) {
            this(key, text, 1);
        }

        public Candidate(String key, String text, int weight) {
            this.key = Objects.requireNonNull(key, "key");
            this.text = Objects.requireNonNull(text, "text");
            this.weight = weight;
        }

        public String getKey() {
            return key;
        }

        public String getText() {
            return text;
        }

        public int getWeight() {
            return weight;
        }
    }

    public static void getConfig(Callback<Map<String, String>> callback) {
        SmartInputJavaBridge.getConfig(requireCallback(callback));
    }

    public static void configure(Map<String, String> patch,
                                 Callback<Map<String, String>> callback) {
        Objects.requireNonNull(patch, "patch");
        SmartInputJavaBridge.configure(new LinkedHashMap<>(patch), requireCallback(callback));
    }

    public static void replaceCandidates(List<Candidate> suggestions,
                                         List<Candidate> corrections,
                                         long ttlMs,
                                         Callback<Map<String, String>> callback) {
        Objects.requireNonNull(suggestions, "suggestions");
        Objects.requireNonNull(corrections, "corrections");
        SmartInputJavaBridge.replaceCandidates(
                new ArrayList<>(suggestions), new ArrayList<>(corrections), ttlMs,
                requireCallback(callback));
    }

    public static void replaceCandidates(List<Candidate> suggestions,
                                         List<Candidate> corrections,
                                         Callback<Map<String, String>> callback) {
        replaceCandidates(suggestions, corrections, 30_000L, callback);
    }

    public static void clearCandidates(Callback<Map<String, String>> callback) {
        SmartInputJavaBridge.clearCandidates(requireCallback(callback));
    }

    public static void resetConfig(Callback<Map<String, String>> callback) {
        SmartInputJavaBridge.resetConfig(requireCallback(callback));
    }

    public static void setSuggestionEnabled(boolean enabled,
                                            Callback<Map<String, String>> callback) {
        configure(singletonPatch("suggestion", enabled ? "1" : "0"), callback);
    }

    public static void setCorrectionEnabled(boolean enabled,
                                            Callback<Map<String, String>> callback) {
        configure(singletonPatch("correction", enabled ? "1" : "0"), callback);
    }

    public static void setLearningEnabled(boolean enabled,
                                          Callback<Map<String, String>> callback) {
        configure(singletonPatch("learning", enabled ? "1" : "0"), callback);
    }

    public static void setLabels(String suggestionLabel, String correctionLabel,
                                 Callback<Map<String, String>> callback) {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("suggestion_label", Objects.requireNonNull(suggestionLabel, "suggestionLabel"));
        patch.put("correction_label", Objects.requireNonNull(correctionLabel, "correctionLabel"));
        configure(patch, callback);
    }

    public static void setSources(String suggestionSource, String correctionSource,
                                  Callback<Map<String, String>> callback) {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("suggestion_source", Objects.requireNonNull(suggestionSource, "suggestionSource"));
        patch.put("correction_source", Objects.requireNonNull(correctionSource, "correctionSource"));
        configure(patch, callback);
    }

    public static void setLimits(int suggestionLimit, int correctionLimit,
                                 Callback<Map<String, String>> callback) {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("suggestion_limit", Integer.toString(suggestionLimit));
        patch.put("correction_limit", Integer.toString(correctionLimit));
        configure(patch, callback);
    }

    public static void setCorrectionPlacement(String placement,
                                               Callback<Map<String, String>> callback) {
        configure(singletonPatch("correction_placement",
                Objects.requireNonNull(placement, "placement")), callback);
    }

    private static Map<String, String> singletonPatch(String key, String value) {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put(key, value);
        return patch;
    }

    private static <T> Callback<T> requireCallback(Callback<T> callback) {
        return Objects.requireNonNull(callback, "callback");
    }
}
