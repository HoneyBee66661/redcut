package com.redcut.core.common.di

import javax.inject.Qualifier

/**
 * Dispatcher qualifiers (spec §4.1 "dispatchers", §9.2's table).
 *
 * ### Why these live in a framework-free module
 *
 * Injecting a dispatcher means the injected thing is a `CoroutineDispatcher`, and
 * `Dispatchers.IO` lives in `kotlinx-coroutines-core` — no Android needed. What WOULD
 * drag a framework in is Hilt's `@Qualifier`, which arrives with an Android classpath.
 * Annotating with JSR-330's `javax.inject.Qualifier` instead (plus a `@Provides` in the
 * :app graph, where Hilt actually lives) keeps :core:common and :domain:* injectable
 * without an Android dependency — the rule that makes the fast test tier possible.
 *
 * Each annotation is `BINARY` retention because qualifiers are read at compile time by
 * the DI processor; keeping them out of the runtime constant pool is what makes them
 * free.
 *
 * ### The mapping they encode (§9.2)
 *
 * | Qualifier | Dispatcher | Work |
 * |---|---|---|
 * | [MainDispatcher] | `Dispatchers.Main.immediate` | UI state and intents |
 * | [DefaultDispatcher] | `Dispatchers.Default` | document compile, commands (pure, CPU-light) |
 * | [IoDispatcher] | `Dispatchers.IO` | project file I/O, probing, thumbnails |
 *
 * Nothing here is a service locator: these are annotations only. The bindings are
 * provided by the composition root, so a test can substitute a `TestDispatcher`
 * without an Android test runner.
 *
 * ### Why only VALUE_PARAMETER and FUNCTION
 *
 * Constructor injection in Kotlin writes `@IoDispatcher private val io: ...`. A
 * `val` constructor parameter is simulaneously a parameter, a property and a
 * backing field, so as long as this annotation may legally target any TWO of
 * those, Kotlin 2.2 warns at every such site (KT-73255) that it currently picks
 * the parameter and will one day apply to both. With warnings-as-errors on, that
 * is not a warning, it is a build failure — verified: narrowing PROPERTY alone
 * left the same warning pointing at FIELD.
 *
 * The fix belongs here, once, rather than as a `@param:` prefix at every
 * injection point forever. Dropping to the two targets that carry actual meaning
 * makes the intent unambiguous: a qualifier marks a dependency (a parameter) or
 * a provider (a function). FIELD is gone too, deliberately — field injection is
 * not how this codebase wires anything, and removing the target turns "someone
 * quietly did it" into a compile error.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class IoDispatcher

/**
 * `Dispatchers.Default` — document compilation and command application (§9.2).
 *
 * Note what is NOT here: compilation is pure and CPU-light, so it does not need IO,
 * and pretending it does would let a blocking call hide in a compile path that §8.1
 * expects to run on every revision change.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class DefaultDispatcher

/**
 * `Dispatchers.Main.immediate` — UI state and intents (§9.2).
 *
 * `immediate` rather than plain `Main`: an intent handled on the main thread already
 * should not be re-posted to the back of the queue, because that is the difference
 * between a tap feeling instant and feeling late.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.VALUE_PARAMETER,
)
annotation class MainDispatcher
