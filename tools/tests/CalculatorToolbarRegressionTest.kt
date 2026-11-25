package org.fcitx.fcitx5.android.input.bar

/** Pure-JVM offline regression harness; this is not an Android/device test. */
private class Counter {
    var transitions = 0
    val states = mutableListOf<KawaiiBarStateMachine.State>()
    fun machine() = KawaiiBarStateMachine.new {
        transitions++
        states += it
    }
}

private fun checkState(m: Any, expected: KawaiiBarStateMachine.State, label: String) {
    check((m as org.fcitx.fcitx5.android.utils.EventStateMachine<*, *, *>).currentState == expected) {
        "$label: got ${(m as org.fcitx.fcitx5.android.utils.EventStateMachine<*, *, *>).currentState}, expected $expected"
    }
}

fun main() {
    var passed = 0
    fun test(name: String, body: () -> Unit) { body(); passed++; println("PASS $name") }

    test("Idle -> calculator Candidate -> exit Idle callback") {
        val c = Counter(); val m = c.machine()
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "enter calculator")
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Idle, "exit calculator")
        check(c.transitions == 2) { "expected two callbacks, got ${c.transitions}" }
    }
    test("calculator with empty candidates/preedit remains Candidate") {
        val c = Counter(); val m = c.machine()
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.PreeditUpdated, KawaiiBarStateMachine.BooleanKey.PreeditEmpty to true, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.PreeditEmpty to true, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "empty calculator")
    }
    test("native non-empty during calculator keeps Candidate, then empty exits") {
        val c = Counter(); val m = c.machine()
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false, KawaiiBarStateMachine.BooleanKey.PreeditEmpty to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "native result retained")
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Idle, "native cleared")
    }
    test("Title priority and return preserves calculator candidate") {
        val c = Counter(); val m = c.machine()
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached)
        checkState(m, KawaiiBarStateMachine.State.Title, "title")
        m.push(KawaiiBarStateMachine.TransitionEvent.WindowDetached)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "title returns candidate")
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Idle, "calculator exits after return")
    }
    test("Title with ordinary native candidate returns Candidate") {
        val m = KawaiiBarStateMachine.new { }
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false)
        m.push(KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached)
        m.push(KawaiiBarStateMachine.TransitionEvent.WindowDetached)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "ordinary candidate")
    }
    test("calculator exit does not wait for preedit to clear") {
        val m = KawaiiBarStateMachine.new { }
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.PreeditUpdated, KawaiiBarStateMachine.BooleanKey.PreeditEmpty to false, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "wait")
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false)
        checkState(m, KawaiiBarStateMachine.State.Idle, "exit")
    }
    test("repeated exit is idempotent") {
        val c = Counter(); val m = c.machine()
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        val n = c.transitions
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Idle, "repeat")
        check(c.transitions == n) { "duplicate exit callback" }
    }
    test("normal non-calculator native semantics") {
        val m = KawaiiBarStateMachine.new { }
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "native candidates")
        m.push(KawaiiBarStateMachine.TransitionEvent.PreeditUpdated, KawaiiBarStateMachine.BooleanKey.PreeditEmpty to true, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Idle, "native cleared")
    }
    test("calculator enter and exit do not replace an attached title") {
        val m = KawaiiBarStateMachine.new { }
        m.push(KawaiiBarStateMachine.TransitionEvent.ExtendedWindowAttached)
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        checkState(m, KawaiiBarStateMachine.State.Title, "calculator enter under title")
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false)
        checkState(m, KawaiiBarStateMachine.State.Title, "calculator exit under title")
        m.push(KawaiiBarStateMachine.TransitionEvent.WindowDetached)
        checkState(m, KawaiiBarStateMachine.State.Idle, "title returns idle")
    }
    test("native candidates cleared during calculator are not restored on exit") {
        val m = KawaiiBarStateMachine.new { }
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to false)
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to true)
        m.push(KawaiiBarStateMachine.TransitionEvent.CandidatesUpdated, KawaiiBarStateMachine.BooleanKey.CandidateEmpty to true)
        checkState(m, KawaiiBarStateMachine.State.Candidate, "calculator covers native clear")
        m.push(KawaiiBarStateMachine.TransitionEvent.CalculatorUpdated, KawaiiBarStateMachine.BooleanKey.CalculatorActive to false)
        checkState(m, KawaiiBarStateMachine.State.Idle, "no stale native candidate")
    }
    println("CalculatorToolbarRegressionTest: $passed tests passed (pure JVM offline; no Android runtime)")
}
