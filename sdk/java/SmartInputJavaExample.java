/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.smart.examples;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.fcitx.fcitx5.android.data.smart.SmartInputApi;

/** In-process Java example. FcitxApplication initializes the backend automatically. */
public final class SmartInputJavaExample {
    private SmartInputJavaExample() {}

    public static void configureThenReplace() {
        Map<String, String> patch = new LinkedHashMap<>();
        patch.put("suggestion", "1");
        patch.put("correction", "1");
        patch.put("suggestion_source", "external");
        patch.put("correction_source", "external");
        patch.put("suggestion_label", "");
        patch.put("correction_label", "");
        patch.put("correction_placement", "prepend");
        SmartInputApi.configure(patch, new SmartInputApi.Callback<Map<String, String>>() {
            @Override public void onSuccess(Map<String, String> config) {
                // Continue only after configuration has succeeded.
                SmartInputApi.replaceCandidates(
                    Arrays.asList(
                        new SmartInputApi.Candidate("天气", "很好", 100),
                        new SmartInputApi.Candidate("天气", "不错", 80)
                    ),
                    Collections.singletonList(new SmartInputApi.Candidate("nihaoo", "你好", 100)),
                    30_000L,
                    new SmartInputApi.Callback<Map<String, String>>() {
                        @Override public void onSuccess(Map<String, String> result) {
                            // On the next candidate rebuild within 30 seconds:
                            // commit 天气 for suggestions, or type nihaoo for correction.
                        }
                        @Override public void onError(Throwable error) {
                            // Show or handle the failure in your Java module.
                        }
                    }
                );
            }
            @Override public void onError(Throwable error) {
                // Show or handle the failure in your Java module.
            }
        });
    }
}
