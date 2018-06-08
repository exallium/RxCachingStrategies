package com.exallium.rx.cachingstrategies

import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.processors.PublishProcessor
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.BehaviorSubject
import org.amshove.kluent.`should be equal to`
import org.junit.Test
import java.util.*
import java.util.concurrent.TimeUnit

class LatestValueCache<V>(valueSingle: Single<V>) {

    private sealed class Cached<out V> {
        object Empty : Cached<Nothing>()
        data class Error(val e: Throwable) : Cached<Nothing>()
        data class Value<out V>(val v: V) : Cached<V>()
    }

    private val requestProcessor = PublishProcessor.create<Unit>()
    private val responseContainer = BehaviorSubject.createDefault<Cached<V>>(Cached.Empty)

    init {
        requestProcessor
                .onBackpressureDrop()
                .flatMap({
                    valueSingle
                            .map<Cached<V>> { Cached.Value(it) }
                            .onErrorReturn { Cached.Error(it) }
                            .toFlowable()
                }, 1)
                .subscribe {
                    synchronized(this@LatestValueCache) {
                        responseContainer.onNext(it)
                    }
                }
    }

    fun get(): Single<V> {
        synchronized(this) {
            val cachedObject = responseContainer.value
            return when (cachedObject) {
                is Cached.Value -> Single.just(cachedObject.v)
                else -> requestValue()
            }
        }
    }

    fun clear() {
        synchronized(this) {
            responseContainer.onNext(Cached.Empty)
        }
    }

    private fun requestValue(): Single<V> {
        request()
        return responseContainer
                .filter { it !== Cached.Empty }.firstOrError().map {
                    when (it) {
                        Cached.Empty -> error("This should never happen!")
                        is Cached.Value -> it.v
                        is Cached.Error -> throw it.e
                    }
                }
    }

    private fun request() {
        responseContainer.onNext(Cached.Empty)
        requestProcessor.onNext(Unit)
    }

}

private const val CACHED_THING = "Cached Thing"

class LatestValueCacheTests {

    private var incr = 0
    private val successWithIncrement = Single.fromCallable {
        incr++
        CACHED_THING
    }

    @Test
    fun `Given a cached value, when I clear, then I should request again on next call`() {
        val testSubject = LatestValueCache(successWithIncrement)
        testSubject.get().blockingGet()

        testSubject.clear()
        testSubject.get().blockingGet()

        incr `should be equal to` 2
    }

    @Test
    fun `Given two quick requests, I should only actually execute the fromCallable body once`() {
        val testSubject = LatestValueCache(successWithIncrement.delay(1, TimeUnit.SECONDS))

        testSubject.get()
        testSubject.get()

        incr `should be equal to` 1
    }

    @Test
    fun `Given 100 quick requests, I should always get Cached Thing`() {
        val testSubject = LatestValueCache(successWithIncrement)

        (1..100).forEach {
            testSubject.get().test().assertValue(CACHED_THING)
        }
    }

    @Test
    fun `Given 2 requests, when first errors, second retries`() {
        val err = Exception()
        val testSubject = LatestValueCache(Single.fromCallable {
            incr++
            if (incr == 1) throw err
            else CACHED_THING
        })

        testSubject.get().test().assertError(err)
        testSubject.get().test().assertValue(CACHED_THING)
        incr `should be equal to` 2
    }

    @Test
    fun `When I attack this cache from a bunch of different threads, I expect correct behaviour`() {
        val random = Random()
        val testSubject = LatestValueCache(successWithIncrement)

        Observable.range(0, 100).flatMap {
            Observable.just(it)
                    .delay { Observable.timer(random.nextInt(4).toLong(), TimeUnit.SECONDS) }
                    .subscribeOn(Schedulers.newThread())
                    .flatMap { testSubject.get().toObservable() }
        }.blockingSubscribe()

        incr `should be equal to` 1
    }

}