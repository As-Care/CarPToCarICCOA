package io.github.shaonialife.carptocar.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View.MeasureSpec
import android.widget.ScrollView

/** Caps the body in the first layout pass, including after rotation. */
class DialogBodyScrollView(context: Context, attrs: AttributeSet) : ScrollView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxHeight = (resources.displayMetrics.heightPixels * 0.46f).toInt()
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val bounded = if (mode == MeasureSpec.UNSPECIFIED || height > maxHeight) {
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        } else {
            heightMeasureSpec
        }
        super.onMeasure(widthMeasureSpec, bounded)
    }
}
