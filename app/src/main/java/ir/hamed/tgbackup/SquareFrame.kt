package ir.hamed.tgbackup

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SquareFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, w)
}
