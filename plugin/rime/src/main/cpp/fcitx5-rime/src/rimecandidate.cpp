/*
 * SPDX-FileCopyrightText: 2017~2017 CSSlayer <wengxt@gmail.com>
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

#include "rimecandidate.h"
#include "rimeengine.h"
#include "rimesuggestions.h"
#include <algorithm>
#include <chrono>
#include <cstring>
#include <fcitx-utils/log.h>
#include <fcitx-utils/standardpaths.h>
#include <fcitx/candidatelist.h>
#include <fstream>
#include <memory>
#include <rime_api.h>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace fcitx::rime {

/**
 * 「梦」智能联想词文件路径（rime 用户目录下）。
 * Java 侧 InputStatsManager 定期把统计到的高频词/自学习习惯码导出到这里，
 * 每行格式：`拼音码<TAB>词<TAB>频率`。native 在候选列表构造时读取并按当前拼音码匹配。
 */
static std::string suggestionFile() {
    return (fcitx::StandardPaths::global().userDirectory(
                fcitx::StandardPathsType::PkgData) /
            "rime" / "suggestion.dict")
        .string();
}

/** 「梦」智能纠错词文件路径（rime 用户目录下）。每行：`拼音码<TAB>纠正词<TAB>频`。 */
const SmartInputOptions &smartOptions() {
    static thread_local SmartInputOptions options;
    return options.load(fcitx::StandardPaths::global().userDirectory(
                            fcitx::StandardPathsType::PkgData) /
                        "rime" / "smart-input.conf");
}

static std::string correctionFile() {
    return (fcitx::StandardPaths::global().userDirectory(
                fcitx::StandardPathsType::PkgData) /
            "rime" / "correction.dict")
        .string();
}
static std::string externalSuggestionFile() { return (fcitx::StandardPaths::global().userDirectory(fcitx::StandardPathsType::PkgData) / "rime" / "external-suggestion.dict").string(); }
static std::string manualCorrectionFile() { return (fcitx::StandardPaths::global().userDirectory(fcitx::StandardPathsType::PkgData) / "rime" / "manual-correction.dict").string(); }
static std::string externalCorrectionFile() { return (fcitx::StandardPaths::global().userDirectory(fcitx::StandardPathsType::PkgData) / "rime" / "external-correction.dict").string(); }
static int64_t nowMs() { return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count(); }

RimeSuggestionWord::RimeSuggestionWord(RimeEngine *engine, std::string text, std::string label)
    : engine_(engine) {
    setText(Text{std::move(text)});
    if (!label.empty()) setComment(Text{std::move(label)});
}

void RimeSuggestionWord::select(InputContext *inputContext) const {
    // commit/updateUI may synchronously replace this candidate list. Capture
    // everything before either call; do not dereference this afterwards.
    const auto word = text().toString();
    auto *state = engine_->state(inputContext);
    if (!state) {
        return;
    }
    state->clear();
    state->setLastEndWordForSuggest(word);
    inputContext->commitString(word);
    state->updateUI(inputContext);
}

RimeCorrectWord::RimeCorrectWord(RimeEngine *engine, std::string text, std::string label)
    : engine_(engine) {
    setText(Text{std::move(text)});
    if (!label.empty()) setComment(Text{std::move(label)});
}

void RimeCorrectWord::select(InputContext *inputContext) const {
    const auto word = text().toString();
    auto *state = engine_->state(inputContext);
    if (!state) {
        return;
    }
    state->clear();
    state->setLastEndWordForSuggest(word);
    inputContext->commitString(word);
    state->updateUI(inputContext);
}

RimeCandidateWord::RimeCandidateWord(RimeEngine *engine,
                                     const RimeCandidate &candidate, int idx)
    : engine_(engine), idx_(idx) {
    setText(Text{candidate.text});
    if (candidate.comment && candidate.comment[0]) {
        setComment(Text{candidate.comment});
    }
}

void RimeCandidateWord::select(InputContext *inputContext) const {
    if (auto *state = engine_->state(inputContext)) {
        state->selectCandidate(inputContext, idx_, /*global=*/false);
    }
}

void RimeCandidateWord::forget(RimeState *state) const {
    state->deleteCandidate(idx_, /*global=*/false);
}

RimeGlobalCandidateWord::RimeGlobalCandidateWord(RimeEngine *engine,
                                                 const RimeCandidate &candidate,
                                                 int idx)
    : engine_(engine), idx_(idx) {
    setText(Text{candidate.text});
    if (candidate.comment && candidate.comment[0]) {
        setComment(Text{candidate.comment});
    }
}

void RimeGlobalCandidateWord::select(InputContext *inputContext) const {
    if (auto *state = engine_->state(inputContext)) {
        state->selectCandidate(inputContext, idx_, /*global=*/true);
    }
}

void RimeGlobalCandidateWord::forget(RimeState *state) const {
    state->deleteCandidate(idx_, /*global=*/true);
}

RimeCandidateList::RimeCandidateList(RimeEngine *engine, InputContext *ic,
                                     const RimeContext &context)
    : engine_(engine), ic_(ic), hasPrev_(context.menu.page_no != 0),
      hasNext_(!context.menu.is_last_page) {
    setPageable(this);
    setBulk(this);
    setActionable(this);
#ifndef FCITX_RIME_NO_HIGHLIGHT_CANDIDATE
    setBulkCursor(this);
#endif

    const auto &menu = context.menu;

    int num_select_keys = menu.select_keys ? strlen(menu.select_keys) : 0;
    bool has_label = RIME_STRUCT_HAS_MEMBER(context, context.select_labels) &&
                     context.select_labels;

    int i;
    for (i = 0; i < menu.num_candidates; ++i) {
        std::string label;
        if (i < menu.page_size && has_label) {
            label = context.select_labels[i];
        } else if (i < num_select_keys) {
            label = std::string(1, menu.select_keys[i]);
        } else {
            label = std::to_string((i + 1) % 10);
        }
        label.append(" ");
        labels_.emplace_back(label);

        candidateWords_.emplace_back(
            std::make_unique<RimeCandidateWord>(engine, menu.candidates[i], i));

        if (i == menu.highlighted_candidate_index) {
            cursor_ = i;
        }
    }

    // ===== 「梦」智能联想/纠错：追加非原生候选 =====
    // 纠错：输入拼音码时，按当前码匹配 correction.dict 追加纠正词候选（RimeCorrectWord）。
    // 联想：有最近上屏尾词时，按尾词匹配 suggestion.dict 追加下一词候选（RimeSuggestionWord）。
    firstPage_ = menu.page_no == 0;
    // Bulk mode always inserts after the FIRST native page, independent of the
    // current Rime page. Preserve all subsequent librime global indices.
    suggestionOffset_ = firstPage_ ? menu.num_candidates : menu.page_size;
    suggestionOffset_ = std::max(0, suggestionOffset_);
    // A correction replaces the whole raw composition; do not drop an already
    // converted prefix in a partially selected Rime sentence.
    if (context.composition.sel_start == 0) {
        appendCorrections();
    }
}

/** 「梦」联想专用候选列表：上屏后无原生候选时独立撑起候选框（纯联想词）。 */
RimeSuggestionList::RimeSuggestionList(RimeEngine *engine, InputContext *ic,
                                       std::string endWord)
    : engine_(engine), ic_(ic) {
    // Use the same bulk path as ordinary pinyin candidates: the bar previews
    // the first items, and the expanded window fetches the remainder by index.
    setBulk(this);
    auto *state = engine_->state(ic_);
    const auto &flags = smartOptions();
    if (!flags.suggestion || !state || !state->smartInputAllowed()) {
        return;
    }
    // Keep independent caches: load() replaces the cache contents, so sharing
    // one store would make the builtin reference point at the external data.
    static thread_local SuggestionStore builtinStore;
    static thread_local SuggestionStore externalStore;
    std::unordered_set<std::string> existing;
    const auto &all = builtinStore.load(suggestionFile(), false);
    const auto &ext = externalStore.load(externalSuggestionFile(), false);
    const auto builtin = flags.suggestion_source == CandidateSource::External ? std::vector<std::string>{} : matchingByEndWord(all, endWord, existing, flags.suggestion_limit);
    const auto externalMatches = externalCandidatesActive(flags, nowMs()) && flags.suggestion_source != CandidateSource::Builtin ? matchingExternalByKey(ext, endWord, existing, flags.suggestion_limit) : std::vector<std::string>{};
    const auto matches = mergeCandidateWords(externalMatches, builtin, flags.suggestion_limit);
    for (const auto &word : matches) {
        labels_.emplace_back("+ ");
        candidateWords_.emplace_back(
            std::make_unique<RimeSuggestionWord>(engine_, word, flags.suggestion_label));
    }
}

/** 追加「梦」智能纠错候选（按当前拼音码匹配 correction.dict）。 */
void RimeCandidateList::appendCorrections() {
    const auto &flags = smartOptions();
    auto *stateForPrivacy = engine_->state(ic_);
    if (!flags.correction || !stateForPrivacy || !stateForPrivacy->smartInputAllowed()) {
        return;
    }
    auto *api = engine_->api();
    if (!api) {
        return;
    }
    auto *state = engine_->state(ic_);
    if (!state) {
        return;
    }
    auto session = state->session(false);
    if (!session) {
        return;
    }
    const char *rawInput = api->get_input(session);
    if (!rawInput || !rawInput[0]) {
        return;
    }
    // Keep independent caches because each load() replaces its store contents.
    static thread_local SuggestionStore builtinStore;
    static thread_local SuggestionStore manualStore;
    static thread_local SuggestionStore externalStore;
    std::unordered_set<std::string> existing;
    if (firstPage_) {
        for (const auto &candidate : candidateWords_) {
            existing.insert(candidate->text().toString());
        }
    } else {
        // Use the same first-page deduplication set on every native page;
        // otherwise inserted counts change and bulk indices select wrong words.
        RimeCandidateListIterator iter{};
        if (api->candidate_list_from_index(session, &iter, 0)) {
            for (int i = 0; i < suggestionOffset_ && api->candidate_list_next(&iter); ++i) {
                if (iter.candidate.text) {
                    existing.insert(iter.candidate.text);
                }
            }
            api->candidate_list_end(&iter);
        }
    }
    const auto &all = builtinStore.load(correctionFile());
    const auto &ext = externalStore.load(externalCorrectionFile());
    const auto builtin = flags.correction_source == CandidateSource::External ? std::vector<std::string>{} : matchingByCode(all, rawInput, existing, flags.correction_limit, CorrectionOptions{flags.habit, flags.neighbor, flags.fuzzy});
    const auto externalMatches = externalCandidatesActive(flags, nowMs()) && flags.correction_source != CandidateSource::Builtin ? matchingExternalByKey(ext, normalizeSuggestionCode(rawInput), existing, flags.correction_limit) : std::vector<std::string>{};
    const auto fallback = mergeCandidateWords(externalMatches, builtin, flags.correction_limit);
    const auto matches = withManualCorrections(manualStore.load(manualCorrectionFile()), rawInput,
                                                existing, fallback, flags);
    for (const auto &word : matches) {
        correctionWords_.emplace_back(
            std::make_unique<RimeCorrectWord>(engine_, word, flags.correction_label));
        if (firstPage_ && !flags.correction_prepend) {
            labels_.emplace_back("+ ");
            candidateWords_.emplace_back(
                std::make_unique<RimeCorrectWord>(engine_, word, flags.correction_label));
        }
    }
    if (firstPage_ && flags.correction_prepend) {
        // insert(begin) reverses the order when repeated. Walk backwards so
        // the visual order matches correctionWords_ and global index mapping.
        for (auto it = matches.rbegin(); it != matches.rend(); ++it) {
            labels_.insert(labels_.begin(), Text{"+ "});
            candidateWords_.insert(
                candidateWords_.begin(),
                std::make_unique<RimeCorrectWord>(engine_, *it, flags.correction_label));
        }
        // Keep the native highlighted candidate unchanged; layout order does
        // not grant an extension permission to take over default selection.
        if (cursor_ >= 0) cursor_ += static_cast<int>(matches.size());
    }
    // Bulk indices must use the same insertion point on ALL native pages.
    // Do this after collecting the first-page deduplication set above.
    if (flags.correction_prepend) suggestionOffset_ = 0;
}

const CandidateWord &RimeCandidateList::candidateFromAll(int idx) const {
    if (idx < 0 || static_cast<size_t>(idx) >= maxSize_) {
        throw std::invalid_argument("Invalid global index");
    }
    const int correctionCount = static_cast<int>(correctionWords_.size());
    const int suggestionCount = static_cast<int>(suggestionWords_.size());
    // 布局：原生候选 [0, suggestionOffset_) → 纠错 → 联想
    const int correctionStart = suggestionOffset_;
    const int suggestionStart = suggestionOffset_ + correctionCount;
    if (idx >= correctionStart && idx - correctionStart < correctionCount) {
        return *correctionWords_[idx - correctionStart];
    }
    if (idx >= suggestionStart && idx - suggestionStart < suggestionCount) {
        return *suggestionWords_[idx - suggestionStart];
    }
    const int nativeIndex = idx >= suggestionStart + suggestionCount
                                ? idx - correctionCount - suggestionCount : idx;
    auto *state = engine_->state(ic_);
    auto session = state ? state->session(false) : 0;
    if (!session) {
        throw std::invalid_argument("Invalid session");
    }
    const auto index = static_cast<size_t>(nativeIndex);
    if (index < globalCandidateWords_.size() && globalCandidateWords_[index]) {
        return *globalCandidateWords_[index];
    }
    auto *api = engine_->api();
    RimeCandidateListIterator iter{};
    if (!api->candidate_list_from_index(session, &iter, nativeIndex)) {
        if (index == globalCandidateWords_.size() &&
            std::all_of(globalCandidateWords_.begin(), globalCandidateWords_.end(),
                        [](const auto &word) { return bool(word); })) {
            maxSize_ = static_cast<size_t>(idx);
        }
        throw std::invalid_argument("Invalid global index");
    }
    if (!api->candidate_list_next(&iter)) {
        api->candidate_list_end(&iter);
        if (index == globalCandidateWords_.size() &&
            std::all_of(globalCandidateWords_.begin(), globalCandidateWords_.end(),
                        [](const auto &word) { return bool(word); })) {
            maxSize_ = static_cast<size_t>(idx);
        }
        throw std::invalid_argument("Invalid global index");
    }
    if (index >= globalCandidateWords_.size()) {
        globalCandidateWords_.resize(index + 1);
    }
    globalCandidateWords_[index] =
        std::make_unique<RimeGlobalCandidateWord>(engine_, iter.candidate, nativeIndex);
    api->candidate_list_end(&iter);
    return *globalCandidateWords_[index];
}

int RimeCandidateList::totalSize() const {
    return maxSize_ == std::numeric_limits<size_t>::max()
               ? -1 : static_cast<int>(maxSize_);
}

bool RimeCandidateList::hasAction(const CandidateWord &candidate) const {
    return dynamic_cast<const RimeCandidateWord *>(&candidate) ||
           dynamic_cast<const RimeGlobalCandidateWord *>(&candidate);
}

std::vector<CandidateAction>
RimeCandidateList::candidateActions(const CandidateWord &candidate) const {
    std::vector<CandidateAction> actions;
    if (!hasAction(candidate)) {
        return actions;
    }
    CandidateAction action;
    action.setId(0);
    action.setText(_("Forget word"));
    actions.push_back(std::move(action));
    return actions;
}

void RimeCandidateList::triggerAction(const CandidateWord &candidate, int id) {
    if (id != 0) {
        return;
    }
    if (auto state = engine_->state(ic_)) {
        if (const auto *rimeCandidate =
                dynamic_cast<const RimeGlobalCandidateWord *>(&candidate)) {
            rimeCandidate->forget(state);
        } else if (const auto *rimeCandidate =
                       dynamic_cast<const RimeCandidateWord *>(&candidate)) {
            rimeCandidate->forget(state);
        }
    }
}

#ifndef FCITX_RIME_NO_HIGHLIGHT_CANDIDATE
int RimeCandidateList::globalCursorIndex() const {
    return -1; // No API available.
}

void RimeCandidateList::setGlobalCursorIndex(int index) {
    const int correctionCount = static_cast<int>(correctionWords_.size());
    const int suggestionCount = static_cast<int>(suggestionWords_.size());
    const int correctionStart = suggestionOffset_;
    const int suggestionStart = suggestionOffset_ + correctionCount;
    // 落在纠错或联想段内：不映射到 native 高亮。
    if (index < 0 ||
        (index >= correctionStart && index - correctionStart < correctionCount) ||
        (index >= suggestionStart && index - suggestionStart < suggestionCount)) {
        return;
    }
    if (index >= suggestionStart + suggestionCount) {
        index -= correctionCount + suggestionCount;
    }
    auto *state = engine_->state(ic_);
    auto session = state ? state->session(false) : 0;
    if (!session) {
        return;
    }
    auto *api = engine_->api();
    api->highlight_candidate(session, index);
}
#endif
} // namespace fcitx::rime
