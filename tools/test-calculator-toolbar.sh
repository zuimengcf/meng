#!/usr/bin/env bash
# 纯 JVM 离线回归；不调用 Gradle/preBuild，不写入 app/src，不代表 Android 设备实测。
set -euo pipefail
REPO="${REPO:-$(cd "$(dirname "$0")/.." && pwd)}"
ROOT="$(mktemp -d /tmp/meng-calculator-toolbar-XXXXXX)"
trap 'rm -rf "$ROOT"' EXIT
mkdir -p "$ROOT/src/org/fcitx/fcitx5/android/data/prefs" "$ROOT/src/timber" "$ROOT/classes"
find_src() { find "$REPO/app/src/main/java" -name "$1" -print -quit; }
STATE=$(find_src KawaiiBarStateMachine.kt); EVENT=$(find_src EventStateMachine.kt)
BAR=$(find_src KawaiiBarComponent.kt); HORIZONTAL=$(find_src HorizontalCandidateComponent.kt)
[[ -n "$STATE" && -n "$EVENT" ]] || { echo "missing real state machine sources" >&2; exit 2; }
cat > "$ROOT/src/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt" <<'EOF'
package org.fcitx.fcitx5.android.data.prefs
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
object AppPrefs {
  private val singleton = AppPrefs
  class Internal { val verboseLog = object : ReadOnlyProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>) = false
  } }
  val internal = Internal()
  fun getInstance() = singleton
}
EOF
cat > "$ROOT/src/timber/Timber.kt" <<'EOF'
package timber.log
object Timber { @JvmStatic fun d(message: String, vararg args: Any?) {} }
EOF
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}"
COMPILER=$(find "$GRADLE_CACHE/wrapper/dists" -name 'kotlin-compiler-embeddable-*.jar' -print -quit)
VERSION="$(basename "$COMPILER" .jar)"; VERSION="${VERSION#kotlin-compiler-embeddable-}"
LIBDIR=$(dirname "$COMPILER"); STDLIB="$LIBDIR/kotlin-stdlib-$VERSION.jar"
[[ -s "$COMPILER" && -s "$STDLIB" ]] || { echo 'missing offline Kotlin compiler/stdlib cache' >&2; exit 2; }
KOTLINC=(java -cp "$LIBDIR/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17)
"${KOTLINC[@]}" -classpath "$STDLIB" -d "$ROOT/classes" \
  "$ROOT/src/org/fcitx/fcitx5/android/data/prefs/AppPrefs.kt" "$ROOT/src/timber/Timber.kt" \
  "$EVENT" "$STATE" "$REPO/tools/tests/CalculatorToolbarRegressionTest.kt"
java -cp "$ROOT/classes:$STDLIB" org.fcitx.fcitx5.android.input.bar.CalculatorToolbarRegressionTestKt

# Also run the repository's dependency-light calculator JUnit tests against real sources.
JUNIT=$(find "$GRADLE_CACHE/caches/modules-2/files-2.1/junit/junit/4.13.2" -name 'junit-4.13.2.jar' -print -quit)
HAMCREST=$(find "$GRADLE_CACHE/caches/modules-2/files-2.1/org.hamcrest/hamcrest-core/1.3" -name 'hamcrest-core-1.3.jar' -print -quit)
ENGINE=$(find_src CalculatorEngine.kt); SESSION=$(find_src CalculatorSession.kt); CURSOR=$(find_src CursorTracker.kt); CURSORRANGE=$(find_src CursorRange.kt)
TEST1=$(find "$REPO/app/src/test" -name CalculatorEngineTest.kt -print -quit 2>/dev/null || true)
TEST2=$(find "$REPO/app/src/test" -name CalculatorSessionTest.kt -print -quit 2>/dev/null || true)
TEST3=$(find "$REPO/app/src/test" -name CalculatorCursorBoundaryTest.kt -print -quit 2>/dev/null || true)
if [[ -s "$JUNIT" && -s "$HAMCREST" && -n "$ENGINE" && -n "$SESSION" && -n "$CURSOR" && -n "$CURSORRANGE" && -n "$TEST1" && -n "$TEST2" && -n "$TEST3" ]]; then
  "${KOTLINC[@]}" -classpath "$ROOT/classes:$STDLIB:$JUNIT:$HAMCREST" -d "$ROOT/calculator-classes" "$ENGINE" "$SESSION" "$CURSORRANGE" "$CURSOR" "$TEST1" "$TEST2" "$TEST3"
  java -cp "$ROOT/calculator-classes:$ROOT/classes:$STDLIB:$JUNIT:$HAMCREST" org.junit.runner.JUnitCore \
    org.fcitx.fcitx5.android.input.calculator.CalculatorEngineTest \
    org.fcitx.fcitx5.android.input.calculator.CalculatorSessionTest \
    org.fcitx.fcitx5.android.input.calculator.CalculatorCursorBoundaryTest
else
  echo 'INFO calculator JUnit sources/cache incomplete; skipped'
fi

# These are source-wiring assertions, not Android UI/runtime tests.
python3 - "$BAR" "$HORIZONTAL" <<'CHECK'
import pathlib, re, sys
bar, horizontal = [pathlib.Path(p).read_text() for p in sys.argv[1:]]
def body(source, name):
    start = source.index('fun ' + name + '(')
    start = source.index('{', start)
    depth = 1
    for i in range(start + 1, len(source)):
        depth += (source[i] == '{') - (source[i] == '}')
        if not depth:
            return source[start + 1:i]
    raise AssertionError('Unclosed function: ' + name)
assert re.search(r'displayedChild\s*=', body(bar, 'switchUiByState'))
assert len(re.findall(r'displayedChild\s*=(?!=)', bar)) == 1, 'UI writes must stay in switchUiByState'
assert 'CalculatorActive to true' in body(bar, 'notifyCandidateNonEmpty')
assert 'CalculatorActive to false' in body(bar, 'notifyCandidateEmpty')
assert 'return' not in body(bar, 'onCandidateUpdate'), 'native candidate state must be tracked during calculation'
update = body(horizontal, 'onCandidateUpdate')
assert update.index('lastNativeCandidates =') < update.index('if (calculatorShowing)')
assert 'data.candidates.copyOf()' in update
show = body(horizontal, 'showCalculatorCandidate')
assert show.index('hideCalculatorCandidate()') < show.index('calculatorShowing = true')
hide = body(horizontal, 'hideCalculatorCandidate')
assert hide.index('onCandidateUpdate(lastNativeCandidates)') < hide.index('bar.notifyCandidateEmpty()')
assert 'bar.onCandidateUpdate(lastNativeCandidates)' in hide
assert 'lastNativeCandidates = FcitxEvent.CandidateListEvent.Data(total = 0)' in body(horizontal, 'onStartInput')
print('PASS Android-facing source-wiring assertions (static only)')
CHECK

echo "Calculator toolbar offline regression passed (not an Android device test)"
