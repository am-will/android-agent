package dev.androidagent.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.View
import java.io.File

/**
 * Renders a Codex pet atlas as an animated avatar. Frames are read from
 * the 8x9 atlas described in [PetAnimation] and advanced on the choreographer
 * using the per-state durations.
 */
class PetAvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
    }
    private var bitmap: Bitmap? = null
    private val srcRect = Rect()
    private val dstRect = RectF()

    private var state: PetAnimation.State = PetAnimation.State.Idle
    private var frameIndex = 0
    private var lastFrameNanos = 0L
    private var animating = false

    private val frameCallback = Choreographer.FrameCallback { now -> advanceFrame(now) }

    /**
     * Loads the spritesheet from disk on the caller's thread (callers should
     * keep this off the UI thread for very large pets) and starts the idle
     * animation. Returns true on success.
     */
    fun loadFromFile(file: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            if (decoded == null) {
                Log.w(TAG, "BitmapFactory returned null for ${file.absolutePath}")
                false
            } else {
                bitmap?.recycle()
                bitmap = decoded
                frameIndex = 0
                lastFrameNanos = 0L
                invalidate()
                if (isAttachedToWindow) {
                    startAnimating()
                }
                true
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to load pet spritesheet ${file.absolutePath}: ${error.message}")
            false
        }
    }

    fun setState(next: PetAnimation.State) {
        if (state == next) return
        state = next
        frameIndex = 0
        lastFrameNanos = 0L
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (bitmap != null) {
            startAnimating()
        }
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val sheet = bitmap ?: return
        val columnsForRow = state.frameCount
        val safeIndex = if (columnsForRow > 0) frameIndex % columnsForRow else 0
        val left = safeIndex * PetAnimation.CELL_WIDTH
        val top = state.row * PetAnimation.CELL_HEIGHT
        srcRect.set(left, top, left + PetAnimation.CELL_WIDTH, top + PetAnimation.CELL_HEIGHT)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0) return
        val cellRatio = PetAnimation.CELL_WIDTH.toFloat() / PetAnimation.CELL_HEIGHT.toFloat()
        val viewRatio = viewWidth / viewHeight
        val drawWidth: Float
        val drawHeight: Float
        if (viewRatio > cellRatio) {
            drawHeight = viewHeight
            drawWidth = drawHeight * cellRatio
        } else {
            drawWidth = viewWidth
            drawHeight = drawWidth / cellRatio
        }
        val dx = (viewWidth - drawWidth) / 2f
        val dy = (viewHeight - drawHeight) / 2f
        dstRect.set(dx, dy, dx + drawWidth, dy + drawHeight)
        canvas.drawBitmap(sheet, srcRect, dstRect, paint)
    }

    private fun startAnimating() {
        if (animating) return
        animating = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopAnimating() {
        if (!animating) return
        animating = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private fun advanceFrame(now: Long) {
        if (!animating) return
        val durations = state.frameDurationsMs
        if (durations.isEmpty()) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
            return
        }
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now
        }
        val elapsedMs = (now - lastFrameNanos) / 1_000_000L
        val currentDuration = durations[frameIndex % durations.size]
        if (elapsedMs >= currentDuration) {
            frameIndex = (frameIndex + 1) % durations.size
            lastFrameNanos = now
            invalidate()
        }
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    companion object {
        private const val TAG = "PetAvatarView"
    }
}
