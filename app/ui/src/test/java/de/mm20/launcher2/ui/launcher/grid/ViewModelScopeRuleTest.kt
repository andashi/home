package de.mm20.launcher2.ui.launcher.grid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * The rule's contract, without the race it prevents: a scope whose work sits
 * on a real IO thread has completed when the rule is done with it. The race
 * itself cannot be forced without loading the host.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelScopeRuleTest {

    /** Waits on a real IO thread, then resumes into Main, like a settings read. */
    private class IoBoundVM : ViewModel() {
        init {
            viewModelScope.launch {
                withContext(Dispatchers.IO) { delay(60_000) }
            }
        }
        val job: Job get() = viewModelScope.coroutineContext.job
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
        }
        assertTrue(vm.job.isCompleted)
    }

    @Test
    fun `a scope on the main looper has completed when the rule ends`() {
        val rule = ViewModelScopeRule()
        lateinit var vm: IoBoundVM
        rule.runAround {
            vm = rule.track(IoBoundVM())
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
        }
        assertFalse(vm.job.isCompleted)
        vm.job.cancel()
    }
}
