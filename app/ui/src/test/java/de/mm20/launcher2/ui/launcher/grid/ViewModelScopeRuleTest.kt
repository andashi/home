package de.mm20.launcher2.ui.launcher.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The rule's contract, without the race it prevents: a scope whose work sits
 * on a real IO thread has completed when the rule is done with it. The race
 * itself cannot be forced without loading the host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelScopeRuleTest {

    /**
     * Waits on a real IO thread, then resumes into Main, like a settings read.
     * Started undispatched, so it is not a queued launch that a cancel ends
     * before it runs: the test waits until the IO section is entered (#180
     * review).
     */
    private class IoBoundVM : ViewModel() {
        val enteredIo = CountDownLatch(1)

        init {
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(Dispatchers.IO) {
                    enteredIo.countDown()
                    delay(60_000)
                }
            }
        }
        val job: Job get() = viewModelScope.coroutineContext.job

        fun awaitIo() = assertTrue("never reached the IO thread", enteredIo.await(5, TimeUnit.SECONDS))
    }

    private fun ViewModelScopeRule.runAround(body: () -> Unit) {
        apply(object : Statement() {
            override fun evaluate() = body()
        }, Description.EMPTY).evaluate()
    }

    @Test
    fun `a scope with a test Main has completed when the rule ends`() {
        val rule = ViewModelScopeRule(StandardTestDispatcher())
        lateinit var vm: IoBoundVM
        rule.runAround {
            vm = rule.track(IoBoundVM())
            vm.awaitIo()
        }
        assertTrue(vm.job.isCompleted)
    }

    @Test
    fun `a scope on the main looper has completed when the rule ends`() {
        val rule = ViewModelScopeRule()
        lateinit var vm: IoBoundVM
        rule.runAround {
            vm = rule.track(IoBoundVM())
            vm.awaitIo()
        }
        assertTrue(vm.job.isCompleted)
    }

    /** Control: without the rule nothing ends the scope, which is the leak. */
    @Test
    fun `an untracked scope is still running after its test`() {
        val rule = ViewModelScopeRule()
        lateinit var vm: IoBoundVM
        rule.runAround {
            vm = IoBoundVM()
            vm.awaitIo()
        }
        assertFalse(vm.job.isCompleted)
        vm.job.cancel()
    }
}
