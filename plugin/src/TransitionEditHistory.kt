package automix

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class TransitionSelection(val custom: Boolean = false, val presetId: String? = null) {
    fun afterManualChange(before: Plan?, after: Plan) = if (before == after) this else TransitionSelection(custom = true)
}

internal data class TransitionEdit(val plan: Plan?, val selection: TransitionSelection)

internal class TransitionEditHistory {
    private val past = mutableStateListOf<TransitionEdit>()
    private val future = mutableStateListOf<TransitionEdit>()
    private var gestureStart by mutableStateOf<TransitionEdit?>(null)
    var selection by mutableStateOf(TransitionSelection())
        private set
    val canUndo get() = gestureStart == null && past.isNotEmpty()
    val canRedo get() = gestureStart == null && future.isNotEmpty()

    fun record(before: TransitionEdit, after: TransitionEdit) {
        if (before == after) return
        selection = after.selection
        if (gestureStart == null) rememberBefore(before)
    }

    fun beginGesture(current: TransitionEdit) {
        if (gestureStart == null) gestureStart = current
    }

    fun finishGesture(current: TransitionEdit) {
        val before = gestureStart ?: return
        gestureStart = null
        if (before != current) rememberBefore(before)
    }

    fun undo(current: TransitionEdit): TransitionEdit? = restore(current, redo = false)

    fun redo(current: TransitionEdit): TransitionEdit? = restore(current, redo = true)

    private fun restore(current: TransitionEdit, redo: Boolean): TransitionEdit? {
        if (gestureStart != null) return null
        val from = if (redo) future else past
        val to = if (redo) past else future
        val edit = from.removeLastOrNull() ?: return null
        to.add(current)
        selection = edit.selection
        return edit
    }

    private fun rememberBefore(before: TransitionEdit) {
        past.add(before)
        if (past.size > 50) past.removeAt(0)
        future.clear()
    }
}
