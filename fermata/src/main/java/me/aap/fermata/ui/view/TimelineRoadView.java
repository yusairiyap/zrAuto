package me.aap.fermata.ui.view;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.R;

/**
 * Draws the vertical "road" connector behind each Fuel Log Timeline card -- a rounded asphalt-grey
 * strip with a dashed center line, mimicking a road running down the whole list. Consecutive rows
 * each draw their own full-height segment, so scrolled together they read as one continuous road
 * (per the reference sketch of a winding road timeline) without needing a shared canvas across
 * items.
 */
public class TimelineRoadView extends View {
	private static final float ROAD_WIDTH_DP = 14f;
	private static final float DASH_ON_DP = 6f;
	private static final float DASH_GAP_DP = 5f;
	private static final float DASH_STROKE_DP = 1.5f;

	private final Paint roadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

	public TimelineRoadView(Context ctx, AttributeSet attrs) {
		super(ctx, attrs);
		float density = ctx.getResources().getDisplayMetrics().density;
		roadPaint.setStyle(Paint.Style.FILL);
		roadPaint.setColor(MaterialColors.getColor(this, R.attr.colorOutline, 0xFF808080));
		dashPaint.setStyle(Paint.Style.STROKE);
		dashPaint.setStrokeWidth(DASH_STROKE_DP * density);
		dashPaint.setColor(MaterialColors.getColor(this, R.attr.colorSurface, 0xFFFFFFFF));
		dashPaint.setPathEffect(new DashPathEffect(
				new float[]{DASH_ON_DP * density, DASH_GAP_DP * density}, 0f));
	}

	@SuppressLint("DrawAllocation")
	@Override
	protected void onDraw(Canvas c) {
		super.onDraw(c);
		float density = getResources().getDisplayMetrics().density;
		float cx = getWidth() / 2f;
		float halfRoad = (ROAD_WIDTH_DP * density) / 2f;
		RectF road = new RectF(cx - halfRoad, 0, cx + halfRoad, getHeight());
		c.drawRoundRect(road, halfRoad, halfRoad, roadPaint);
		c.drawLine(cx, 0, cx, getHeight(), dashPaint);
	}
}
