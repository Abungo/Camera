package com.renskylabs.camera2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Custom View to display and control an arc-shaped slider for focus or zoom.
 * It provides a normalized progress value (0.0 to 1.0) via a listener.
 */
public class ArcSliderView extends View {

    private Paint arcPaint;
    private Paint indicatorPaint;
    private RectF arcBounds;
    
    // Geometry parameters
    private final float START_ANGLE = 225f; // Where the arc starts (bottom-left)
    private final float SWEEP_ANGLE = 90f;  // Total angle the arc covers
    private final float ARC_WIDTH = 12f;    // Thickness of the arc line
    private final float INDICATOR_RADIUS = 18f; // Size of the indicator circle
    
    private float currentProgress = 0.0f; // Current value: 0.0 (start) to 1.0 (end)
    private OnSliderChangeListener listener;

    public interface OnSliderChangeListener {
        void onSliderValueChanged(float progress);
    }

    public ArcSliderView(Context context) {
        super(context);
        init();
    }

    public ArcSliderView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        // Initialize Paint for the static arc track
        arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(ARC_WIDTH);
        arcPaint.setColor(Color.argb(150, 255, 255, 255)); // Semi-transparent white track
        
        // Initialize Paint for the moving indicator
        indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        indicatorPaint.setStyle(Paint.Style.FILL);
        indicatorPaint.setColor(Color.WHITE);
        
        arcBounds = new RectF();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        
        // CORRECTED DRAWING LOGIC: Ensures the arc fits within the view's height (h=150dp)
        float centerX = w / 2f;
        
        // Define the radius to be slightly smaller than the view height (h)
        float radius = h * 0.9f; 
        
        // Set the Y center position so the arc (bottom of the circle) sits near the bottom edge of the view (h).
        //float centerY = h - radius + ARC_WIDTH; 
        float centerY = h / 1.1f; // Moves arc slightly upward so it’s visible
        arcBounds.set(centerX - radius, centerY - radius, 
                      centerX + radius, centerY + radius);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Draw the Arc Track
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, arcPaint);

        // 2. Calculate the current angle for the indicator
        float indicatorAngle = START_ANGLE + (currentProgress * SWEEP_ANGLE);
        
        // Convert the angle (in degrees) to radians for Math.sin/cos
        double angleRad = Math.toRadians(indicatorAngle);

        // 3. Calculate indicator (x, y) position using trigonometry
        float radius = arcBounds.width() / 2f;
        float centerX = arcBounds.centerX();
        float centerY = arcBounds.centerY();

        float indicatorX = (float) (centerX + radius * Math.cos(angleRad));
        float indicatorY = (float) (centerY + radius * Math.sin(angleRad));

        // 4. Draw the Indicator Circle
        canvas.drawCircle(indicatorX, indicatorY, INDICATOR_RADIUS, indicatorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_MOVE || event.getAction() == MotionEvent.ACTION_DOWN) {
            
            float x = event.getX();
            float y = event.getY();
            
            // Calculate the angle (in radians) from the center to the touch point
            double angleRad = Math.atan2(y - arcBounds.centerY(), x - arcBounds.centerX());
            float angleDeg = (float) Math.toDegrees(angleRad);
            
            // Normalize the angle to a 0-360 range
            if (angleDeg < 0) {
                angleDeg += 360;
            }
            
            // Clamp and map the angle to the arc range (START_ANGLE to START_ANGLE + SWEEP_ANGLE)
            float endAngle = START_ANGLE + SWEEP_ANGLE;
            
            float clampedAngle;
            
            if (angleDeg < START_ANGLE) {
                clampedAngle = START_ANGLE;
            } else if (angleDeg > endAngle) {
                clampedAngle = endAngle;
            } else {
                clampedAngle = angleDeg;
            }
            
            // Map the clamped angle to the progress (0.0 to 1.0)
            currentProgress = (clampedAngle - START_ANGLE) / SWEEP_ANGLE;
            
            // Notify the listener
            if (listener != null) {
                listener.onSliderValueChanged(currentProgress);
            }
            
            invalidate(); // Redraw the view with the new indicator position
            return true;
        }
        return super.onTouchEvent(event);
    }

    public void setOnSliderChangeListener(OnSliderChangeListener listener) {
        this.listener = listener;
    }
    
    // Optional: Set the current position programmatically (e.g., reset)
    public void setProgress(float progress) {
        this.currentProgress = Math.max(0.0f, Math.min(1.0f, progress));
        invalidate();
    }
}
