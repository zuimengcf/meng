/*
 * SPDX-FileCopyrightText: 2017~2017 CSSlayer <wengxt@gmail.com>
 *
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
#ifndef _FCITX_RIMESTATE_H_
#define _FCITX_RIMESTATE_H_

#include "rimesession.h"
#include <fcitx-utils/key.h>
#include <fcitx/event.h>
#include <fcitx/globalconfig.h>
#include <fcitx/inputcontextmanager.h>
#include <fcitx/inputcontextproperty.h>
#include <chrono>
#include <functional>
#include <memory>
#include <rime_api.h>
#include <string>
#include <string_view>
#include <vector>

#define RIME_ASCII_MODE "ascii_mode"

namespace fcitx::rime {

class RimeEngine;

class RimeState : public InputContextProperty {
public:
    RimeState(RimeEngine *engine, InputContext &ic);

    virtual ~RimeState();

    void clear();
    void activate();
    void keyEvent(KeyEvent &event);
    void selectCandidate(InputContext *inputContext, int idx, bool global);
    void deleteCandidate(int idx, bool global);
    bool getStatus(const std::function<void(const RimeStatus &)> &);
    void updatePreedit(InputContext *ic, const RimeContext &context);
    void updateUI(InputContext *ic);
    void release();
    void commitInput(InputContext *ic);
    void commitComposing(InputContext *ic);
    void commitPreedit(InputContext *ic);

    /** 记录最近一次上屏的尾词（供智能联想预测下一词）。 */
    void setLastEndWordForSuggest(std::string word);
    std::string lastEndWordForSuggest() const;
    bool smartInputAllowed() const;
    std::string subMode();
    std::string subModeLabel();
    void toggleLatinMode();
    void setLatinMode(bool latin);
    void selectSchema(const std::string &schemaId);
    RimeSessionId session(bool requestNewSession = true);

    void snapshot();
    void restore();
    std::string currentSchema();
    void addChangedOption(std::string_view option);
    void showChangedOptions();

private:
    std::string asciiModeName(bool abbrev);
    void maybeSyncProgramNameToSession();
    std::vector<std::string> snapshotOptions(const std::string &schema);

    std::string lastMode_;
    RimeEngine *engine_;
    InputContext &ic_;
    std::shared_ptr<RimeSessionHolder> session_;
    /** 最近一次上屏的尾词（联想匹配键）。 */
    std::string lastEndWordForSuggest_;
    std::chrono::steady_clock::time_point lastSmartCommit_{};

    std::string savedCurrentSchema_;
    std::vector<std::string> savedOptions_;
    std::vector<std::string> changedOptions_;
};
} // namespace fcitx::rime

#endif // _FCITX_RIMESTATE_H_
