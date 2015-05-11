package com.wiki.crashserver.util;

import java.util.Collections;
import java.util.List;

public class EditResult {
    private final List<PatchResult> results;

    public EditResult(List<PatchResult> results) {
        this.results = Collections.unmodifiableList(results);
    }

    public List<PatchResult> getResults() {
        return results;
    }

    public static class PatchResult {
        private final String patch;
        private final boolean result;

        public PatchResult(String patch, boolean result) {
            this.patch = patch;
            this.result = result;
        }

        public String getPatch() {
            return patch;
        }

        public boolean isResult() {
            return result;
        }
    }
}
