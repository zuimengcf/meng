/*
 * SPDX-FileCopyrightText: 2017~2017 CSSlayer <wengxt@gmail.com>
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#ifndef _FCITX_RIMECANDIDATE_H_
#define _FCITX_RIMECANDIDATE_H_

#include "rimeengine.h"
#include "rimestate.h"
#include <fcitx/candidateaction.h>
#include <fcitx/candidatelist.h>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace fcitx::rime {

class RimeCandidateWord : public CandidateWord {
public:
    RimeCandidateWord(RimeEngine *engine, const RimeCandidate &candidate,
                      int idx);

    void select(InputContext *inputContext) const override;
    void forget(RimeState *state) const;

private:
    RimeEngine *engine_;
    int idx_;
};

class RimeGlobalCandidateWord : public CandidateWord {
public:
    RimeGlobalCandidateWord(RimeEngine *engine, const RimeCandidate &candidate,
                            int idx);

    void select(InputContext *inputContext) const override;
    void forget(RimeState *state) const;

private:
    RimeEngine *engine_;
    int idx_;
};

/**
 * 「梦」智能联想候选词（非 rime 原生候选）。
 *
 * 这类候选来自应用层统计（bigram 尾词共现），由 Java 侧导出到 rime 用户目录的
 * 联想词文件（suggestion.dict 纯文本，每行一个词）后，由 native 的 RimeCandidateList
 * 构造时读取并追加。它的 select() 不走 librime 的 select_candidate（那些索引只对应
 * rime 原生候选），而是直接把联想文本 commit 上屏。
 */
class RimeSuggestionWord final : public CandidateWord {
public:
    RimeSuggestionWord(RimeEngine *engine, std::string text, std::string label = {});
    void select(InputContext *inputContext) const override;

private:
    RimeEngine *engine_;
};

/**
 * 「梦」智能纠错候选词（非 rime 原生候选）。
 *
 * 与 RimeSuggestionWord 同构：来自 correction.dict（拼音码→纠正词习惯码），
 * select() 直接 commit 上屏，不走 librime select_candidate。
 */
class RimeCorrectWord final : public CandidateWord {
public:
    RimeCorrectWord(RimeEngine *engine, std::string text, std::string label = {});
    void select(InputContext *inputContext) const override;

private:
    RimeEngine *engine_;
};

/**
 * 「梦」联想专用候选列表（仅含联想词，用于上屏后无原生候选时独立撑起候选框）。
 *
 * 上屏后拼音码清空、num_candidates==0，rime 不产生原生候选；此时若存在
 * 最近上屏尾词，用尾词查 suggestion.dict 生成一组 RimeSuggestionWord，
 * 独立构成整个候选列表，供 Java 侧 PagedCandidatesUi 渲染。
 */
class RimeSuggestionList final : public CandidateList, public BulkCandidateList {
public:
    RimeSuggestionList(RimeEngine *engine, InputContext *ic,
                       std::string endWord);

    const Text &label(int idx) const override {
        checkIndex(idx);
        return labels_[idx];
    }
    const CandidateWord &candidate(int idx) const override {
        checkIndex(idx);
        return *candidateWords_[idx];
    }
    int size() const override { return candidateWords_.size(); }
    int cursorIndex() const override { return 0; }
    const CandidateWord &candidateFromAll(int idx) const override {
        return candidate(idx);
    }
    int totalSize() const override { return size(); }
    CandidateLayoutHint layoutHint() const override {
        return CandidateLayoutHint::NotSet;
    }

private:
    void checkIndex(int idx) const {
        if (idx < 0 || idx >= size()) {
            throw std::invalid_argument("invalid index");
        }
    }

    RimeEngine *engine_;
    InputContext *ic_;
    std::vector<Text> labels_;
    std::vector<std::unique_ptr<CandidateWord>> candidateWords_;
};

class RimeCandidateList final : public CandidateList,
                                public ActionableCandidateList,
                                public PageableCandidateList,
                                public BulkCandidateList
#ifndef FCITX_RIME_NO_HIGHLIGHT_CANDIDATE
    ,
                                public BulkCursorCandidateList
#endif
{
public:
    RimeCandidateList(RimeEngine *engine, InputContext *ic,
                      const RimeContext &context);

    const Text &label(int idx) const override {
        checkIndex(idx);
        return labels_[idx];
    }

    const CandidateWord &candidate(int idx) const override {
        checkIndex(idx);
        return *candidateWords_[idx];
    }
    int size() const override { return candidateWords_.size(); }

    int cursorIndex() const override { return cursor_; }

    CandidateLayoutHint layoutHint() const override { return layout_; }

    bool hasPrev() const override { return hasPrev_; }
    bool hasNext() const override { return hasNext_; }
    void prev() override {
        KeyEvent event(ic_, Key(FcitxKey_Page_Up));
        if (auto state = engine_->state(ic_)) {
            state->keyEvent(event);
        }
    }
    void next() override {
        KeyEvent event(ic_, Key(FcitxKey_Page_Down));
        if (auto state = engine_->state(ic_)) {
            state->keyEvent(event);
        }
    }

    bool usedNextBefore() const override { return true; }

    const CandidateWord &candidateFromAll(int idx) const override;
    int totalSize() const override;

#ifndef FCITX_RIME_NO_HIGHLIGHT_CANDIDATE
    int globalCursorIndex() const override;
    void setGlobalCursorIndex(int index) override;
#endif

    bool hasAction(const CandidateWord &candidate) const override;
    std::vector<CandidateAction>
    candidateActions(const CandidateWord &candidate) const override;
    void triggerAction(const CandidateWord &candidate, int id) override;

    /** 追加「梦」智能纠错候选（按当前拼音码匹配 correction.dict）。 */
    void appendCorrections();

    /** 每次候选列表最多追加的联想词条数。 */
    static constexpr int kMaxSuggestionCount = 5;

private:
    void checkIndex(int idx) const {
        if (idx < 0 || idx >= size()) {
            throw std::invalid_argument("invalid index");
        }
    }

    RimeEngine *engine_;
    InputContext *ic_;
    std::vector<Text> labels_;
    bool hasPrev_ = false;
    bool hasNext_ = false;
    CandidateLayoutHint layout_ = CandidateLayoutHint::NotSet;
    int cursor_ = -1;

    std::vector<std::unique_ptr<CandidateWord>> candidateWords_;
    std::vector<std::unique_ptr<RimeSuggestionWord>> suggestionWords_;
    std::vector<std::unique_ptr<RimeCorrectWord>> correctionWords_;
    int suggestionOffset_ = 0;
    int correctionOffset_ = 0;
    bool firstPage_ = true;

    mutable size_t maxSize_ = std::numeric_limits<size_t>::max();
    mutable std::vector<std::unique_ptr<RimeGlobalCandidateWord>>
        globalCandidateWords_;
};
} // namespace fcitx::rime

#endif // _FCITX_RIMECANDIDATE_H_
