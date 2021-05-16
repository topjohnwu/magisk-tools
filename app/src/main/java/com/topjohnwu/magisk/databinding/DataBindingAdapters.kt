package com.topjohnwu.magisk.databinding

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.Toolbar
import androidx.cardview.widget.CardView
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.ImageViewCompat
import androidx.databinding.BindingAdapter
import androidx.databinding.InverseBindingAdapter
import androidx.databinding.InverseBindingListener
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.recyclerview.widget.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.di.ServiceLocator
import com.topjohnwu.magisk.ktx.coroutineScope
import com.topjohnwu.magisk.ktx.replaceRandomWithSpecial
import com.topjohnwu.magisk.utils.TextHolder
import com.topjohnwu.superuser.internal.UiThreadHandler
import com.topjohnwu.widget.IndeterminateCheckBox
import kotlinx.coroutines.*
import kotlin.math.roundToInt

@BindingAdapter("gone")
fun setGone(view: View, gone: Boolean) {
    view.isGone = gone
}

@BindingAdapter("invisible")
fun setInvisible(view: View, invisible: Boolean) {
    view.isInvisible = invisible
}

@BindingAdapter("goneUnless")
fun setGoneUnless(view: View, goneUnless: Boolean) {
    setGone(view, goneUnless.not())
}

@BindingAdapter("invisibleUnless")
fun setInvisibleUnless(view: View, invisibleUnless: Boolean) {
    setInvisible(view, invisibleUnless.not())
}

@BindingAdapter("markdownText")
fun setMarkdownText(tv: TextView, text: CharSequence) {
    tv.coroutineScope.launch(Dispatchers.IO) {
        ServiceLocator.markwon.setMarkdown(tv, text.toString())
    }
}

@BindingAdapter("markdownText")
fun setMarkdownText(tv: TextView, text: TextHolder) {
    tv.coroutineScope.launch(Dispatchers.IO) {
        ServiceLocator.markwon.setMarkdown(tv, text.getText(tv.resources).toString())
    }
}

@BindingAdapter("onNavigationClick")
fun setOnNavigationClickedListener(view: Toolbar, listener: View.OnClickListener) {
    view.setNavigationOnClickListener(listener)
}

@BindingAdapter("srcCompat")
fun setImageResource(view: ImageView, @DrawableRes resId: Int) {
    view.setImageResource(resId)
}

@BindingAdapter("srcCompat")
fun setImageResource(view: ImageView, drawable: Drawable) {
    view.setImageDrawable(drawable)
}

@BindingAdapter("movieBehavior", "movieBehaviorText")
fun setMovieBehavior(view: TextView, isMovieBehavior: Boolean, text: String) {
    (view.tag as? Job)?.cancel()
    view.tag = null
    if (isMovieBehavior) {
        view.tag = GlobalScope.launch(Dispatchers.Main.immediate) {
            while (true) {
                delay(150)
                view.text = text.replaceRandomWithSpecial()
            }
        }
    } else {
        view.text = text
    }
}

@BindingAdapter("onTouch")
fun setOnTouchListener(view: View, listener: View.OnTouchListener) {
    view.setOnTouchListener(listener)
}

@BindingAdapter("scrollToLast")
fun setScrollToLast(view: RecyclerView, shouldScrollToLast: Boolean) {

    fun scrollToLast() = UiThreadHandler.handler.postDelayed({
        view.scrollToPosition(view.adapter?.itemCount?.minus(1) ?: 0)
    }, 30)

    fun wait(callback: () -> Unit) {
        UiThreadHandler.handler.postDelayed(callback, 1000)
    }

    fun RecyclerView.Adapter<*>.setListener() {
        val observer = object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                scrollToLast()
            }
        }
        registerAdapterDataObserver(observer)
        view.setTag(R.id.recyclerScrollListener, observer)
    }

    fun RecyclerView.Adapter<*>.removeListener() {
        val observer =
            view.getTag(R.id.recyclerScrollListener) as? RecyclerView.AdapterDataObserver ?: return
        unregisterAdapterDataObserver(observer)
    }

    fun trySetListener(): Unit = view.adapter?.setListener() ?: wait { trySetListener() }

    if (shouldScrollToLast) {
        trySetListener()
    } else {
        view.adapter?.removeListener()
    }
}

@BindingAdapter("isEnabled")
fun setEnabled(view: View, isEnabled: Boolean) {
    view.isEnabled = isEnabled
}

@BindingAdapter("error")
fun TextInputLayout.setErrorString(error: String) {
    val newError = error.let { if (it.isEmpty()) null else it }
    if (this.error == null && newError == null) return
    this.error = newError
}

// md2

@BindingAdapter(
    "android:layout_marginLeft",
    "android:layout_marginTop",
    "android:layout_marginRight",
    "android:layout_marginBottom",
    "android:layout_marginStart",
    "android:layout_marginEnd",
    requireAll = false
)
fun View.setMargins(
    marginLeft: Int?,
    marginTop: Int?,
    marginRight: Int?,
    marginBottom: Int?,
    marginStart: Int?,
    marginEnd: Int?
) = updateLayoutParams<ViewGroup.MarginLayoutParams> {
    marginLeft?.let { leftMargin = it }
    marginTop?.let { topMargin = it }
    marginRight?.let { rightMargin = it }
    marginBottom?.let { bottomMargin = it }
    marginStart?.let { this.marginStart = it }
    marginEnd?.let { this.marginEnd = it }
}

@BindingAdapter("nestedScrollingEnabled")
fun RecyclerView.setNestedScrolling(enabled: Boolean) {
    isNestedScrollingEnabled = enabled
}

@BindingAdapter("isSelected")
fun View.isSelected(isSelected: Boolean) {
    this.isSelected = isSelected
}

@BindingAdapter("dividerVertical", "dividerHorizontal", requireAll = false)
fun RecyclerView.setDividers(dividerVertical: Drawable?, dividerHorizontal: Drawable?) {
    if (dividerHorizontal != null) {
        DividerItemDecoration(context, LinearLayoutManager.HORIZONTAL).apply {
            setDrawable(dividerHorizontal)
        }.let { addItemDecoration(it) }
    }
    if (dividerVertical != null) {
        DividerItemDecoration(context, LinearLayoutManager.VERTICAL).apply {
            setDrawable(dividerVertical)
        }.let { addItemDecoration(it) }
    }
}

@BindingAdapter("icon")
fun Button.setIconRes(res: Int) {
    (this as MaterialButton).setIconResource(res)
}

@BindingAdapter("icon")
fun Button.setIcon(drawable: Drawable) {
    (this as MaterialButton).icon = drawable
}

@BindingAdapter("strokeWidth")
fun MaterialCardView.setCardStrokeWidthBound(stroke: Float) {
    strokeWidth = stroke.roundToInt()
}

@BindingAdapter("onMenuClick")
fun Toolbar.setOnMenuClickListener(listener: Toolbar.OnMenuItemClickListener) {
    setOnMenuItemClickListener(listener)
}

@BindingAdapter("onCloseClicked")
fun Chip.setOnCloseClickedListenerBinding(listener: View.OnClickListener) {
    setOnCloseIconClickListener(listener)
}

@BindingAdapter("progressAnimated")
fun ProgressBar.setProgressAnimated(newProgress: Int) {
    val animator = tag as? ValueAnimator
    animator?.cancel()

    ValueAnimator.ofInt(progress, newProgress).apply {
        interpolator = FastOutSlowInInterpolator()
        addUpdateListener { progress = it.animatedValue as Int }
        tag = this
    }.start()
}

@BindingAdapter("android:text")
fun TextView.setTextSafe(text: Int) {
    if (text == 0) this.text = null else setText(text)
}

@BindingAdapter("android:onLongClick")
fun View.setOnLongClickListenerBinding(listener: () -> Unit) {
    setOnLongClickListener {
        listener()
        true
    }
}

@BindingAdapter("strikeThrough")
fun TextView.setStrikeThroughEnabled(useStrikeThrough: Boolean) {
    paintFlags = if (useStrikeThrough) {
        paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
    } else {
        paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
    }
}

@BindingAdapter("spanCount")
fun RecyclerView.setSpanCount(count: Int) {
    when (val lama = layoutManager) {
        is GridLayoutManager -> lama.spanCount = count
        is StaggeredGridLayoutManager -> lama.spanCount = count
    }
}

@BindingAdapter("state")
fun setState(view: IndeterminateCheckBox, state: Boolean?) {
    if (view.state != state)
        view.state = state
}

@InverseBindingAdapter(attribute = "state")
fun getState(view: IndeterminateCheckBox) = view.state

@BindingAdapter("stateAttrChanged")
fun setListeners(
    view: IndeterminateCheckBox,
    attrChange: InverseBindingListener
) {
    view.setOnStateChangedListener { _, _ ->
        attrChange.onChange()
    }
}

@BindingAdapter("cardBackgroundColorAttr")
fun CardView.setCardBackgroundColorAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    setCardBackgroundColor(tv.data)
}

@BindingAdapter("tint")
fun ImageView.setTint(color: Int) {
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(color))
}

@BindingAdapter("tintAttr")
fun ImageView.setTintAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(tv.data))
}

@BindingAdapter("textColorAttr")
fun TextView.setTextColorAttr(attr: Int) {
    val tv = TypedValue()
    context.theme.resolveAttribute(attr, tv, true)
    setTextColor(tv.data)
}
