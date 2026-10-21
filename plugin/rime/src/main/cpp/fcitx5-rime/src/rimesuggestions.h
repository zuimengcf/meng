/* SPDX-License-Identifier: LGPL-2.1-or-later */
#ifndef FCITX_RIME_SUGGESTIONS_H
#define FCITX_RIME_SUGGESTIONS_H

#include <algorithm>
#include <charconv>
#include <cctype>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <functional>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <string_view>
#include <unordered_set>
#include <vector>

namespace fcitx::rime {
/**
 * A next-word list is an overlay, so delete may dismiss it only when that
 * overlay is actually visible and non-empty. Keep this policy independent of
 * fcitx objects so the edge cases can be tested without an input context.
 */
inline bool shouldDismissSuggestionOnDelete(bool keyRelease,
                                             bool hasComposition,
                                             bool hasVisibleSuggestions,
                                             bool isDeleteKey) {
    return !keyRelease && !hasComposition && hasVisibleSuggestions && isDeleteKey;
}

struct SuggestionEntry { std::string code; std::string word; int frequency; };
inline constexpr size_t kMaxSuggestionCodeLength = 64;

inline std::string normalizeSuggestionCode(std::string_view code) {
    std::string result;
    for (unsigned char c : code) {
        if (c >= 'A' && c <= 'Z') result.push_back(static_cast<char>(c - 'A' + 'a'));
        else if (c >= 'a' && c <= 'z') result.push_back(static_cast<char>(c));
        else if (c != '\'' && c != ' ') return {};
        if (result.size() > kMaxSuggestionCodeLength) return {};
    }
    return result;
}

class SuggestionStore {
public:
    // pinyinCode=false is required for suggestion.dict, whose first column is a
    // Chinese end-word rather than a pinyin code. The one-argument call remains valid.
    const std::vector<SuggestionEntry> &load(const std::filesystem::path &path,
                                              bool pinyinCode = true) {
        std::error_code error;
        const auto modified = std::filesystem::last_write_time(path, error);
        const auto size = error ? 0 : std::filesystem::file_size(path, error);
        if (error || size > kMaxBytes) { invalidate(); return entries_; }
        if (valid_ && path == path_ && modified == modified_ && size == size_ &&
            pinyinCode == pinyinCode_) return entries_;
        std::ifstream input(path);
        if (!input) { invalidate(); return entries_; }
        std::vector<SuggestionEntry> parsed;
        std::string line; size_t bytes = 0, lines = 0;
        while (lines++ < kMaxLines && std::getline(input, line)) {
            bytes += line.size() + 1; if (bytes > kMaxBytes) break;
            if (!line.empty() && line.back() == '\r') line.pop_back();
            if (line.empty() || line.front() == '#') continue;
            const auto tab1 = line.find('\t');
            const auto tab2 = tab1 == std::string::npos ? std::string::npos : line.find('\t', tab1 + 1);
            if (tab2 == std::string::npos) continue;
            std::string code = line.substr(0, tab1);
            if (pinyinCode) code = normalizeSuggestionCode(code);
            std::string word = line.substr(tab1 + 1, tab2 - tab1 - 1);
            const std::string_view number(line.data() + tab2 + 1, line.size() - tab2 - 1);
            int frequency = 0;
            const auto r = std::from_chars(number.data(), number.data() + number.size(), frequency);
            const auto hasControl = [](std::string_view s) {
                for (unsigned char c : s) if (c < 0x20 || c == 0x7f) return true;
                return false;
            };
            if (code.empty() || (pinyinCode && code.size() > kMaxSuggestionCodeLength) ||
                word.empty() || hasControl(code) || hasControl(word) ||
                r.ec != std::errc{} || r.ptr != number.data() + number.size() || frequency <= 0) continue;
            parsed.push_back({std::move(code), std::move(word), frequency});
        }
        std::stable_sort(parsed.begin(), parsed.end(), [](const auto &a, const auto &b) {
            return a.frequency > b.frequency;
        });
        entries_ = std::move(parsed); path_ = path; modified_ = modified; size_ = size;
        pinyinCode_ = pinyinCode; valid_ = true; return entries_;
    }
private:
    void invalidate() { entries_.clear(); valid_ = false; }
    static constexpr size_t kMaxBytes = 4 * 1024 * 1024, kMaxLines = 50000;
    bool valid_ = false, pinyinCode_ = true; std::filesystem::path path_;
    std::filesystem::file_time_type modified_{}; uintmax_t size_ = 0;
    std::vector<SuggestionEntry> entries_;
};

struct CorrectionOptions { bool habit = true; bool neighbor = true; bool fuzzy = true; };

namespace detail {
inline bool oneEdit(std::string_view a, std::string_view b) {
    if (a == b) return false;
    if (a.size() == b.size()) {
        size_t first = 0;
        while (first < a.size() && a[first] == b[first]) ++first;
        if (first == a.size()) return false;
        return first + 1 < a.size() && a[first] == b[first + 1] &&
               a[first + 1] == b[first] && a.substr(first + 2) == b.substr(first + 2);
    }
    if (a.size() + 1 == b.size() || b.size() + 1 == a.size()) {
        auto longer = a.size() > b.size() ? a : b; auto shorter = a.size() > b.size() ? b : a;
        size_t i = 0, j = 0, skips = 0;
        while (i < longer.size() && j < shorter.size()) {
            if (longer[i] == shorter[j]) {
                ++i;
                ++j;
            } else { ++i; if (++skips > 1) return false; }
        }
        return true;
    }
    return false;
}
inline bool adjacentKey(char a, char b) {
    static constexpr std::string_view rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    static constexpr double offsets[] = {0.0, 0.25, 0.75};
    int ar = -1, br = -1, ai = -1, bi = -1;
    for (int r = 0; r < 3; ++r) {
        const auto ap = rows[r].find(a), bp = rows[r].find(b);
        if (ap != std::string_view::npos) { ar = r; ai = static_cast<int>(ap); }
        if (bp != std::string_view::npos) { br = r; bi = static_cast<int>(bp); }
    }
    if (ar < 0 || br < 0 || std::abs(ar - br) > 1) return false;
    const double distance = (offsets[ar] + ai) - (offsets[br] + bi);
    return distance >= -1.01 && distance <= 1.01;
}
inline bool neighborEdit(std::string_view a, std::string_view b) {
    if (a.size() != b.size()) return false;
    size_t diff = 0;
    for (size_t i = 0; i < a.size(); ++i) {
        if (a[i] == b[i]) continue;
        if (!adjacentKey(a[i], b[i]) || ++diff > 2) return false;
    }
    // A second adjacent-key slip is useful on normal-length words, but is
    // deliberately rejected for short codes to avoid broad false positives.
    if (diff == 1) return true;
    if (diff != 2 || a.size() < 5) return false;
    return true;
}
inline bool longCodeSubstitution(std::string_view a, std::string_view b) {
    // This deliberately conservative fallback is only for long full-pinyin
    // codes: keep the first syllable prefix and permit one non-neighbor slip.
    if (a.size() != b.size() || a.size() < 6 || a.substr(0, 2) != b.substr(0, 2)) {
        return false;
    }
    size_t diff = 0;
    for (size_t i = 2; i < a.size(); ++i) {
        if (a[i] == b[i]) continue;
        if (adjacentKey(a[i], b[i]) || ++diff > 1) return false;
    }
    return diff == 1;
}
inline bool fuzzyPinyin(std::string_view a, std::string_view b) {
    static constexpr std::pair<std::string_view, std::string_view> rules[] = {
        {"zh", "z"}, {"ch", "c"}, {"sh", "s"}, {"an", "ang"},
        {"en", "eng"}, {"in", "ing"}, {"n", "l"}, {"f", "h"}
    };
    for (const auto &[from, to] : rules) {
        for (const bool reverse : {false, true}) {
            const auto old = reverse ? to : from;
            const auto replacement = reverse ? from : to;
            for (size_t pos = a.find(old); pos != std::string_view::npos;
                 pos = a.find(old, pos + 1)) {
                std::string candidate(a);
                candidate.replace(pos, old.size(), replacement);
                if (candidate == b) return true;
            }
        }
    }
    return false;
}
} // namespace detail

inline std::vector<std::string> matchingSuggestions(const std::vector<SuggestionEntry> &entries,
    std::string_view input, std::unordered_set<std::string> existing, size_t limit = 5) {
    const auto normalized = normalizeSuggestionCode(input); std::vector<std::string> out;
    if (normalized.empty() || normalized.size() < 2 || limit == 0) return out;
    for (const auto &e : entries) if (e.code.size() <= kMaxSuggestionCodeLength &&
        e.code.starts_with(normalized) && existing.insert(e.word).second) {
        out.push_back(e.word); if (out.size() >= limit) break;
    }
    return out;
}

inline std::vector<std::string> matchingByEndWord(const std::vector<SuggestionEntry> &entries,
    std::string_view endWord, std::unordered_set<std::string> existing, size_t limit = 5) {
    std::vector<std::string> out; if (endWord.empty() || limit == 0) return out;
    for (const auto &e : entries) if (e.code == endWord && existing.insert(e.word).second) {
        out.push_back(e.word); if (out.size() >= limit) break;
    }
    return out;
}

inline std::vector<std::string> matchingByCode(const std::vector<SuggestionEntry> &entries,
    std::string_view code, std::unordered_set<std::string> existing, size_t limit = 5,
    CorrectionOptions options = {}) {
    std::string input = normalizeSuggestionCode(code); std::vector<const SuggestionEntry *> exact, fuzzy, typo, longSubstitution;
    if (input.empty() || input.size() > kMaxSuggestionCodeLength || limit == 0) return {};
    for (const auto &e : entries) {
        if (e.code.size() > kMaxSuggestionCodeLength) continue;
        if (e.code == input) { if (options.habit) exact.push_back(&e); continue; }
        if (input.size() < 3 || e.code.size() < 3) continue; // short codes are exact-only
        // A fuzzy relation can also look like one insertion/deletion (zh/z,
        // an/ang, etc.); classify it first so disabling fuzzy cannot be
        // bypassed by the typo strategy.
        if (detail::fuzzyPinyin(e.code, input)) {
            if (options.fuzzy) fuzzy.push_back(&e);
            continue;
        }
        if (options.neighbor && detail::neighborEdit(e.code, input)) typo.push_back(&e);
        else if (options.neighbor && detail::oneEdit(e.code, input)) typo.push_back(&e);
        else if (options.neighbor && detail::longCodeSubstitution(e.code, input)) longSubstitution.push_back(&e);
    }
    auto byFrequency = [](const auto *a, const auto *b) { return a->frequency > b->frequency; };
    std::stable_sort(exact.begin(), exact.end(), byFrequency);
    std::stable_sort(fuzzy.begin(), fuzzy.end(), byFrequency);
    std::stable_sort(typo.begin(), typo.end(), byFrequency);
    std::stable_sort(longSubstitution.begin(), longSubstitution.end(), byFrequency);
    std::vector<std::string> out;
    auto append = [&](const std::vector<const SuggestionEntry *> &v) {
        for (auto *e : v) if (out.size() < limit && existing.insert(e->word).second) out.push_back(e->word);
    };
    append(exact); append(fuzzy); append(typo); append(longSubstitution); return out;
}

enum class CandidateSource { Builtin, External, Merge };

struct SmartInputOptions {
    bool suggestion = false, correction = false, neighbor = false, fuzzy = false, habit = false;
    int suggestion_limit = 15, correction_limit = 5;
    std::string suggestion_label, correction_label;
    CandidateSource suggestion_source = CandidateSource::Builtin;
    CandidateSource correction_source = CandidateSource::Builtin;
    bool correction_prepend = false;
    int64_t external_expires_at_ms = 0;
    const SmartInputOptions &load(const std::filesystem::path &path) {
        std::error_code ec;
        const auto stamp = std::filesystem::last_write_time(path, ec);
        if (!ec && valid_ && path == path_ && stamp == modified_) return *this;
        suggestion = correction = neighbor = fuzzy = habit = false;
        suggestion_limit = 15; correction_limit = 5;
        suggestion_label.clear(); correction_label.clear();
        suggestion_source = correction_source = CandidateSource::Builtin;
        correction_prepend = false; external_expires_at_ms = 0;
        std::ifstream in(path); std::string line;
        while (std::getline(in, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            auto p = line.find('='); if (p == std::string::npos) continue;
            auto key = line.substr(0, p), val = line.substr(p + 1);
            auto on = val == "1" || val == "true" || val == "yes";
            if (key == "suggestion") suggestion = on;
            else if (key == "correction") correction = on;
            else if (key == "neighbor") neighbor = on;
            else if (key == "fuzzy") fuzzy = on;
            else if (key == "habit") habit = on;
            else if (key == "suggestion_limit") suggestion_limit = boundedInt(val, 15);
            else if (key == "correction_limit") correction_limit = boundedInt(val, 5);
            else if (key == "suggestion_label") suggestion_label = validLabel(val) ? val : "";
            else if (key == "correction_label") correction_label = validLabel(val) ? val : "";
            else if (key == "suggestion_source") suggestion_source = source(val);
            else if (key == "correction_source") correction_source = source(val);
            else if (key == "correction_placement") correction_prepend = val == "prepend";
            else if (key == "external_expires_at_ms") {
                int64_t parsed = 0; auto r = std::from_chars(val.data(), val.data() + val.size(), parsed);
                if (r.ec == std::errc{} && r.ptr == val.data() + val.size() && parsed > 0) external_expires_at_ms = parsed;
            }
        }
        path_ = path; modified_ = stamp; valid_ = !ec;
        return *this;
    }
private:
    static int boundedInt(const std::string &v, int fallback) {
        int n = 0; auto r = std::from_chars(v.data(), v.data() + v.size(), n);
        return r.ec == std::errc{} && r.ptr == v.data() + v.size() ? std::clamp(n, 0, 50) : fallback;
    }
    static bool validLabel(const std::string &v) {
        if (v.size() > 64) return false;
        for (unsigned char c : v) if (c == '\n' || c == '\r' || c == 0 || c < 0x20 || c == 0x7f) return false;
        return true;
    }
    static CandidateSource source(const std::string &v) {
        if (v == "external") return CandidateSource::External;
        if (v == "merge") return CandidateSource::Merge;
        return CandidateSource::Builtin;
    }
    bool valid_ = false;
    std::filesystem::path path_;
    std::filesystem::file_time_type modified_{};
};

// Shared by candidate construction and key handling.  Keeping one file-backed
// option loader ensures a preference change is observed by both paths.
const SmartInputOptions &smartOptions();

inline bool externalCandidatesActive(const SmartInputOptions &options, int64_t nowMs) {
    return options.external_expires_at_ms > 0 && nowMs < options.external_expires_at_ms;
}

inline std::vector<std::string> matchingExternalByKey(
    const std::vector<SuggestionEntry> &entries, std::string_view key,
    std::unordered_set<std::string> existing = {}, size_t limit = 5) {
    std::vector<std::string> out;
    if (key.empty() || limit == 0) return out;
    for (const auto &e : entries) if (e.code == key && existing.insert(e.word).second) {
        out.push_back(e.word); if (out.size() >= limit) break;
    }
    return out;
}

inline std::vector<std::string> mergeCandidateWords(const std::vector<std::string> &external,
    const std::vector<std::string> &builtin, size_t limit) {
    std::vector<std::string> out; std::unordered_set<std::string> seen;
    for (const auto &group : {std::cref(external), std::cref(builtin)})
        for (const auto &word : group.get()) if (out.size() < limit && seen.insert(word).second) out.push_back(word);
    return out;
}

// Persistent, explicitly enabled user rules are independent of learned heuristics,
// candidate source selection and external-candidate TTL. They offer candidates, not auto-commits.
inline std::vector<std::string> withManualCorrections(
    const std::vector<SuggestionEntry> &manualEntries, std::string_view input,
    const std::unordered_set<std::string> &existing,
    const std::vector<std::string> &fallback, const SmartInputOptions &options) {
    if (!options.correction || options.correction_limit <= 0) return {};
    const auto manual = matchingExternalByKey(manualEntries, normalizeSuggestionCode(input),
                                              existing, options.correction_limit);
    return mergeCandidateWords(manual, fallback, options.correction_limit);
}

// Return the final one or two Han characters, only when the input ends in Han.
// Invalid UTF-8, punctuation/space endings, and non-Han code points return {}.
inline std::string extractSuggestionTail(std::string_view text) {
    struct Unit { size_t begin; uint32_t cp; };
    std::vector<Unit> units;
    for (size_t i = 0; i < text.size();) {
        const size_t begin = i; unsigned char c = static_cast<unsigned char>(text[i++]);
        uint32_t cp = 0; size_t need = 0;
        if (c < 0x80) cp = c;
        else if (c >= 0xC2 && c <= 0xDF) { cp = c & 0x1F; need = 1; }
        else if (c >= 0xE0 && c <= 0xEF) { cp = c & 0x0F; need = 2; }
        else if (c >= 0xF0 && c <= 0xF4) { cp = c & 7; need = 3; }
        else return {};
        if (i + need > text.size()) return {};
        for (size_t j = 0; j < need; ++j) {
            const unsigned char d = static_cast<unsigned char>(text[i++]);
            if ((d & 0xC0) != 0x80) return {};
            cp = (cp << 6) | (d & 0x3F);
        }
        if ((need == 2 && cp < 0x800) || (need == 3 && cp < 0x10000) ||
            cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return {};
        units.push_back({begin, cp});
    }
    if (units.empty() || units.back().cp < 0x4E00 || units.back().cp > 0x9FFF) return {};
    size_t first = units.size() - 1;
    if (first && units[first - 1].cp >= 0x4E00 && units[first - 1].cp <= 0x9FFF) --first;
    return std::string(text.substr(units[first].begin));
}
} // namespace fcitx::rime
#endif
