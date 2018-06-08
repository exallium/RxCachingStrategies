package com.exallium.rx.cachingstrategies

import io.reactivex.Observable
import org.amshove.kluent.`should be equal to`
import org.junit.Test

class Samples {
    @Test
    fun `When I subscribe to the same cold observable multiple times, it fires off its OnSubscribe body multiple times`() {
        var incr = 0
        val obs = Observable.fromCallable {
            incr++
            "Hello, World!"
        }

        obs.test()
        obs.test()

        incr `should be equal to` 2
    }

    @Test
    fun `When I cache and resubscribe, I get back everything`() {
        var incr = 0
        val exception = Exception()
        val obs = Observable.range(1, 10)
                .doOnNext { incr++ }
                .map {
                    if (it == 10) throw exception else it
                }.cache()

        obs.test().assertValueCount(9).assertError(exception)
        obs.test().assertValueCount(9).assertError(exception)

        incr `should be equal to` 10
    }

}