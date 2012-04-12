/*
 * Copyright (C) 2009 TBA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.glmap;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.CountDownTimer;
import android.util.FloatMath;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

public class GLMapView extends GLSurfaceView {
	// private static String TAG = "GLMapView";
	// private static final boolean DEBUG = true;

	private float cursorZ;
	private boolean multitouch = false;
	private GLMapRenderer mRenderer;
	private GestureDetector gestureDetector;
	public float zoomFactor;

	public GLMapView(Context context) {
		super(context);

		// this.setEGLConfigChooser(8, 8, 8, 8, 8, 1);
		this.setEGLConfigChooser(new MultisampleConfigChooser());

		this.setEGLContextClientVersion(2);

		// Set the renderer responsible for frame rendering
		mRenderer = new GLMapRenderer(this);
		setRenderer(mRenderer);
		// setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);

		// Gesture detection
		this.gestureDetector = new GestureDetector(new MapGestureDetector(this));
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		int action = event.getAction();

		if (action == MotionEvent.ACTION_POINTER_2_DOWN) {
			this.multitouch = true;
			float dx = event.getX(1) - event.getX(0);
			float dy = event.getY(1) - event.getY(0);
			float z = FloatMath.sqrt(dx * dx + dy * dy);
			this.cursorZ = z;
		}
		else if (action == MotionEvent.ACTION_MOVE && this.multitouch
		      && event.getPointerCount() > 1) {
			float dx = event.getX(1) - event.getX(0);
			float dy = event.getY(1) - event.getY(0);
			float z = FloatMath.sqrt(dx * dx + dy * dy);
			if (z / this.cursorZ > 0.5 && z / this.cursorZ < 2.0) {
				this.mRenderer.zoom(z / this.cursorZ);
			}
			this.cursorZ = z;
		}
		else if (action == MotionEvent.ACTION_UP && this.multitouch) {
			this.multitouch = false;
		}
		else if (action == MotionEvent.ACTION_POINTER_2_UP) {
			this.multitouch = false;
		}
		else {
			this.gestureDetector.onTouchEvent(event);
		}
		return true;
	}

	private class MapGestureDetector extends SimpleOnGestureListener {
		private GLMapView mapView;
		private float xScroll;
		private float yScroll;
		public Scroller scroller;

		public MapGestureDetector(GLMapView mapView) {
			this.mapView = mapView;
			this.scroller = new Scroller(mapView.getContext(), new DecelerateInterpolator());
		}

		@Override
		public boolean onDown(MotionEvent e) {
			this.scroller.forceFinished(true);
			return true;
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
			this.mapView.mRenderer.move(-distanceX, distanceY);
			return true;
		}

		public boolean scroll() {
			if (this.scroller.isFinished())
				return false;

			this.scroller.computeScrollOffset();
			float curX = this.scroller.getCurrX();
			float curY = this.scroller.getCurrY();
			float x = curX - this.xScroll;
			float y = curY - this.yScroll;

			if (Math.abs(x) >= 1 || Math.abs(y) >= 1) {
				this.mapView.mRenderer.move(x, -y);

				this.xScroll = curX;
				this.yScroll = curY;
			}

			return true;
		}

		@Override
		public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {

			int w = this.mapView.getWidth();
			int h = this.mapView.getHeight();

			this.xScroll = 0;
			this.yScroll = 0;

			this.scroller.fling(0, 0, Math.round(velocityX) / 2, Math.round(velocityY) / 2,
			                    -10 * w, 10 * w, -10 * h, 10 * h);

			new CountDownTimer(2000, 20) {
				@Override
				public void onTick(long tick) {
					if (!scroll())
						this.cancel();
				}

				@Override
				public void onFinish() {

				}
			}.start();

			return true;
		}
	}
}
