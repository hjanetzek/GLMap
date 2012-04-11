package com.android.glmap;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.RejectedExecutionException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import org.mapsforge.core.Tile;

import android.app.Application;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.AsyncTask;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.View;

public class GLMapRenderer implements GLSurfaceView.Renderer {
	private static String TAG = "GLMapRenderer";

	private final boolean debug = false;

	private final int NROF_TILES_X = 8;
	private final int NROF_TILES_Y = 8;
	private final int NROF_TILES = NROF_TILES_X * NROF_TILES_Y;

	private static final int POLYGON_VERTICES_DATA_POS_OFFSET = 0;
	private static final int LINE_VERTICES_DATA_POS_OFFSET = 0;
	private static final int LINE_VERTICES_DATA_TEX_OFFSET = 12;
	private static final int LINE_VERTICES_DATA_COLOR1_OFFSET = 0;
	private static final int LINE_VERTICES_DATA_COLOR2_OFFSET = 4;
	private static final int POLY_VERTEX_SIZE = 8;
	
	private GLMapView mapview;
	private GLMapTile[][] tiles;
	private boolean initialized;
	private GLMapLoader glMapLoader;
	private FloatBuffer fullscreenCoordsBuffer;

	private int gLineProgram;
	private int gLinevPositionHandle;
	private int gLinetexPositionHandle;
	private int gLineColorHandle;
	private int gLinecPositionHandle;
	private int gLineWidthHandle;
	private int gLineHeightOffsetHandle;
	private int gLineScaleXHandle;
	private int gLineScaleYHandle;
	private int gPolygonProgram;
	private int gPolygonvPositionHandle;
	private int gPolygoncPositionHandle;
	private int gPolygonScaleXHandle;
	private int gPolygonScaleYHandle;
	private int gPolygonFillProgram;
	private int gPolygonFillvPositionHandle;
	private int gPolygonFillColorHandle;

	private float xPos = 980073.56f;
	private float yPos = 6996566.0f;
	private float zPos = 0.0008f;
	private double tile_size = 500.0;
	private int width, height;
	private double xScrollStart = 0.0;
	private double yScrollStart = 0.0;

	private long lastDraw = 0;
	private boolean gles_shader = true;

	private int loadShader(int shaderType, String source) {
		int shader = GLES20.glCreateShader(shaderType);
		if (shader != 0) {
			GLES20.glShaderSource(shader, source);
			GLES20.glCompileShader(shader);
			int[] compiled = new int[1];
			GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
			if (compiled[0] == 0) {
				Log.e(TAG, "Could not compile shader " + shaderType + ":");
				Log.e(TAG, GLES20.glGetShaderInfoLog(shader));
				GLES20.glDeleteShader(shader);
				shader = 0;
			}
		}
		return shader;
	}

	private int createProgram(String vertexSource, String fragmentSource) {
		int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
		if (vertexShader == 0) {
			return 0;
		}

		int pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
		if (pixelShader == 0) {
			return 0;
		}

		int program = GLES20.glCreateProgram();
		if (program != 0) {
			GLES20.glAttachShader(program, vertexShader);
			checkGlError("glAttachShader");
			GLES20.glAttachShader(program, pixelShader);
			checkGlError("glAttachShader");
			GLES20.glLinkProgram(program);
			int[] linkStatus = new int[1];
			GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
			if (linkStatus[0] == 0) {
				Log.e(TAG, "Could not link program: ");
				Log.e(TAG, GLES20.glGetProgramInfoLog(program));
				GLES20.glDeleteProgram(program);
				program = 0;
			}
		}
		return program;
	}

	private void checkGlError(String op) {
		int error;
		while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
			Log.e(TAG, op + ": glError " + error);
			// throw new RuntimeException(op + ": glError " + error);
		}
	}

	public GLMapRenderer(GLMapView mapview) {
		this.mapview = mapview;
		this.glMapLoader = new GLMapLoader();
	}

	private void init() {
		// Set up the program for rendering lines
		gLineProgram = createProgram(Shaders.gLineVertexShader,
		                             Shaders.gLineFragmentShader);
		if (gLineProgram == 0) {
			gles_shader = false;
			Log.e(TAG, "Could not create program.");
			gLineProgram = createProgram(Shaders.gLineVertexShaderSimple,
			                             Shaders.gLineFragmentShaderSimple);
			if (gLineProgram == 0) {
				Log.e(TAG, "Could not create program.");
				return;
			}
		}

		gLinecPositionHandle = GLES20.glGetUniformLocation(gLineProgram, "u_center");
		gLineScaleXHandle = GLES20.glGetUniformLocation(gLineProgram, "scaleX");
		gLineScaleYHandle = GLES20.glGetUniformLocation(gLineProgram, "scaleY");
		gLineHeightOffsetHandle = GLES20.glGetUniformLocation(gLineProgram, "height_offset");
		gLineWidthHandle = GLES20.glGetUniformLocation(gLineProgram, "width");
		gLinevPositionHandle = GLES20.glGetAttribLocation(gLineProgram, "a_position");
		gLinetexPositionHandle = GLES20.glGetAttribLocation(gLineProgram, "a_st");
		gLineColorHandle = GLES20.glGetAttribLocation(gLineProgram, "a_color");
		checkGlError("glGetAttribLocation");

		// Set up the program for rendering polygons
		gPolygonProgram = createProgram(Shaders.gPolygonVertexShader,
		                                Shaders.gPolygonFragmentShader);
		if (gPolygonProgram == 0) {
			Log.e(TAG, "Could not create program.");
			return;
		}
		gPolygoncPositionHandle = GLES20.glGetUniformLocation(gPolygonProgram, "u_center");
		gPolygonScaleXHandle = GLES20.glGetUniformLocation(gPolygonProgram, "scaleX");
		gPolygonScaleYHandle = GLES20.glGetUniformLocation(gPolygonProgram, "scaleY");
		gPolygonvPositionHandle = GLES20.glGetAttribLocation(gPolygonProgram, "a_position");
		checkGlError("glGetUniformLocation");

		// Set up the program for filling polygons
		gPolygonFillProgram = createProgram(Shaders.gPolygonFillVertexShader,
		                                    Shaders.gPolygonFillFragmentShader);
		if (gPolygonFillProgram == 0) {
			Log.e(TAG, "Could not create program.");
			return;
		}
		gPolygonFillvPositionHandle = GLES20.glGetAttribLocation(gPolygonFillProgram,
		                                                         "a_position");
		gPolygonFillColorHandle = GLES20.glGetUniformLocation(gPolygonFillProgram, "u_color");
		checkGlError("glGetUniformLocation");

		// Set up vertex buffer objects
		int[] vboIds = new int[3 * NROF_TILES];
		GLES20.glGenBuffers(3 * NROF_TILES, vboIds, 0);

		// Set up the tile handles
		tiles = new GLMapTile[NROF_TILES_X][];
		GLMapTile tile;
		for (int i = 0; i < NROF_TILES_X; i++) {
			tiles[i] = new GLMapTile[NROF_TILES_Y];
			for (int j = 0; j < NROF_TILES_Y; j++) {
				tile = new GLMapTile();
				int n = i + j * NROF_TILES_X;
				tile.lineVBO = vboIds[3 * n];
				tile.colorVBO = vboIds[3 * n + 1];
				tile.polygonVBO = vboIds[3 * n + 2];
				tile.nrofLineVertices = 0;
				tile.nrofPolygonVertices = 0;
				tile.x = -1;
				tile.y = -1;
				tile.newData = false;
				tiles[i][j] = tile;
			}
		}

		float[] coords = { -1.0f, 1.0f, 1.0f, 1.0f, -1.0f, -1.0f, 1.0f, -1.0f };

		fullscreenCoordsBuffer = ByteBuffer.allocateDirect(8 * 4)
		      .order(ByteOrder.nativeOrder())
		      .asFloatBuffer().put(coords);

		// Set general settings
		// GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
		//
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glDepthFunc(GLES20.GL_LEQUAL);

		GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
		GLES20.glDisable(GLES20.GL_STENCIL_TEST);

		GLES20.glCullFace(GLES20.GL_BACK);
		GLES20.glFrontFace(GLES20.GL_CW);

		Log.i(TAG, "Initialization complete.");
		this.initialized = true;
	}

	public void onDrawFrame(GL10 gl) {

		lastDraw = System.currentTimeMillis();

		if (this.changed) {
			mapRenderFrame();

			if (debug)
				Log.i(TAG, "draw took: " + (System.currentTimeMillis() - lastDraw));
		}
	}

	public void onSurfaceChanged(GL10 glUnused, int w, int h) {
		this.width = w;
		this.height = h;

		GLES20.glViewport(0, 0, w, h);
		checkGlError("GLES20.glViewport");

		mapMove(this.xPos, this.yPos, this.zPos, true);

		changed = true;
	}

	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		init();
	}

	private Boolean lock = new Boolean(true);
	private boolean changed = true;

	public void move(float x, float y) {
		this.xPos = this.xPos - x / ((this.zPos / 2) * this.width);
		this.yPos = this.yPos - y / ((this.zPos / 2) * this.height);

		mapMove(this.xPos, this.yPos, this.zPos, false);

		synchronized (this.lock) {
			this.changed = true;
		}
	}

	public boolean scroll() {
		if (this.mapview.scroller.isFinished())
			return false;

		this.mapview.scroller.computeScrollOffset();
		float x = (float) this.xScrollStart +
		      this.mapview.scroller.getCurrX() / (this.zPos * this.width);
		float y = (float) this.yScrollStart +
		      this.mapview.scroller.getCurrY() / (this.zPos * this.height);
		if (Math.abs(x) >= 1 || Math.abs(y) >= 1) {
			this.xPos = x;
			this.yPos = y;
			mapMove(this.xPos, this.yPos, this.zPos, false);
		}
		synchronized (this.lock) {
			this.changed = true;
		}
		return true;
	}

	public void fling(float velocityX, float velocityY) {
		this.xScrollStart = this.xPos;
		this.yScrollStart = this.yPos;
		this.mapview.scroller.fling(0, 0, Math.round(velocityX), Math.round(velocityY),
		                            -10 * this.width, 10 * this.width, -10 * this.height,
		                            10 * this.height);

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
	}

	public void zoom(float z) {
		this.zPos = this.zPos * z;

		mapMove(this.xPos, this.yPos, this.zPos, false);

		synchronized (this.lock) {
			this.changed = true;
		}
	}

	synchronized int mapMove(float x, float y, float z, boolean sync) {
		if (!this.initialized)
			return 0;

		int xx = (int) ((x - 0.5 * tile_size * NROF_TILES_X) / tile_size);
		int yy = (int) ((y - 0.5 * tile_size * NROF_TILES_Y) / tile_size);

		GLMapTile tmp;

		// Check if any new tiles need to be loaded
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				
				int tx, ty, s, t;

				tx = xx + i;
				ty = yy + j;

				s = tx % NROF_TILES_X;
				t = ty % NROF_TILES_Y;
				
				if (tiles[s][t].loading) continue;
				
				if ((tiles[s][t].x != tx) || (tiles[s][t].y != ty)) {

					// Load tile from disk
					tiles[s][t].x = tx;
					tiles[s][t].y = ty;

					String tilename = tx + "_" + ty;

					if (sync) {
						if (glMapLoader.loadMapTile(tilename, tiles[s][t])) {
							tiles[s][t].newData = true;
						}
					} else {
						// async loading
						loadTile(tilename, tiles[s][t], this);
					}
				}
			}
		}

		return 0;
	}

	private void loadTile(final String name, final GLMapTile tile, final GLMapRenderer renderer) {
		tile.loading = true;
		tile.newData = false;
		AsyncTask<Void, Void, Integer> task = new AsyncTask<Void, Void, Integer>() {
			@Override
			protected void onPreExecute() {
			}

			@Override
			protected Integer doInBackground(Void... args0) {
				if (glMapLoader.loadMapTile(name, tile)) {
					tile.newData = true;
					tile.loading = false;
					renderer.changed = true;
				}

				tile.loading = false;
				return null;
			}
		};

		tile.task = task;

		try {
			task.execute();
		} catch (RejectedExecutionException e) {
			task.cancel(true);
		}
	}

	private synchronized void mapRenderFrame() {

		float x = this.xPos;
		float y = this.yPos;
		float z = zPos;

		synchronized (this.lock) {
			this.changed = false;
		}

		// Check if any new tiles need to be loaded into graphics memory
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				if (tiles[i][j].newData) {
					synchronized (this) {
						if (tiles[i][j].nrofLineVertices > 0) {
							// Upload line data to graphics core vertex buffer object
							GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].lineVBO);
							GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
							                    tiles[i][j].nrofLineVertices * 20,
							                    tiles[i][j].lineVerticesBuffer,
							                    GLES20.GL_DYNAMIC_DRAW);
							checkGlError("glBufferData1 " + +tiles[i][j].nrofLineVertices + " ");

							GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].colorVBO);
							GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
							                    tiles[i][j].nrofLineVertices * 8,
							                    tiles[i][j].colorVerticesBuffer,
							                    GLES20.GL_DYNAMIC_DRAW);

							checkGlError("glBufferData1 " + +tiles[i][j].nrofLineVertices + " ");

						}
						// Upload polygon data to graphics core vertex buffer object
						if (tiles[i][j].nrofPolygonVertices > 0) {
							GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].polygonVBO);
							GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,
							                    tiles[i][j].nrofPolygonVertices * POLY_VERTEX_SIZE,
							                    tiles[i][j].polygonVerticesBuffer,
							                    GLES20.GL_DYNAMIC_DRAW);
							checkGlError("glBufferData2 " + +tiles[i][j].nrofPolygonVertices + " ");

						}
						tiles[i][j].newData = false;
					}
				}
			}
		}

		// Clear the buffers
		GLES20.glClearColor(244 / 255f, 244 / 255f, 240 / 255f, 1.0f);
		GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);

		int cnt = 0;
		byte[] colors = new byte[20];

		// draw all tiles polygons of one layer into one stencil buffer
		// avoiding stencil buffer clears.
		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				if (tiles[i][j].loading || tiles[i][j].newData)
					continue;

				if (tiles[i][j].polygonLayers == null)
					continue;
				
				for (PolygonLayer layer : tiles[i][j].polygonLayers) {
					boolean found = false;
					byte color = layer.rgba[0];
					for (int c = 0; c < cnt; c++)
						if (colors[c] == color)
							found = true;

					if (!found)
						colors[cnt++] = color;
				}
			}
		}

		GLES20.glEnable(GLES20.GL_STENCIL_TEST);

		for (int c = 0; c < cnt; c++) {

			// Draw into stencil buffer to find covered areas
			// This uses the method described here:
			// http://www.glprogramming.com/red/chapter14.html#name13

			GLES20.glDisable(GLES20.GL_DEPTH_TEST);
			GLES20.glDisable(GLES20.GL_CULL_FACE);
			GLES20.glDisable(GLES20.GL_BLEND);
			GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT);

			GLES20.glStencilMask(0x01);
			GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INVERT);
			GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, ~0);
			//
			// GLES20.glStencilOp(GLES20.GL_INVERT, GLES20.GL_INVERT,
			// GLES20.GL_INVERT);
			// GLES20.glStencilFunc(GLES20.GL_NEVER, 0, 1);

			// GLES20.glClearStencil(0);

			GLES20.glColorMask(false, false, false, false);
			GLES20.glDepthMask(false);
			
			GLES20.glUseProgram(gPolygonProgram);
			GLES20.glUniform4f(gPolygoncPositionHandle, x, y, 0.0f, 0.0f);
			GLES20.glUniform1f(gPolygonScaleXHandle, z * (float) (this.height)
			      / (float) (this.width));
			GLES20.glUniform1f(gPolygonScaleYHandle, z);

			PolygonLayer drawn = null;

			// draw polygons
			for (int i = 0; i < NROF_TILES_X; i++) {
				for (int j = 0; j < NROF_TILES_Y; j++) {
					if (tiles[i][j].loading || tiles[i][j].newData)
						continue;

					if (tiles[i][j].polygonLayers == null)
						continue;
					
					PolygonLayer layer = null;

					for (int l = 0, n = tiles[i][j].polygonLayers.size(); l < n; l++) {
						layer = tiles[i][j].polygonLayers.get(l);

						if (layer.rgba[0] == colors[c]) {
							break;
						}
						layer = null;
					}

					if (layer == null)
						continue;

					drawn = layer;

					GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].polygonVBO);

					GLES20.glVertexAttribPointer(gPolygonvPositionHandle, 2, GLES20.GL_FLOAT,
					                             false, 0, POLYGON_VERTICES_DATA_POS_OFFSET);

					GLES20.glEnableVertexAttribArray(gPolygonvPositionHandle);

					// for (int k = 0; k < layer.nrofPolygons; k++) {
					// GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN,
					// layer.polygonIndex[k * 2],
					// layer.polygonIndex[k * 2 + 1]);
					// }

					GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN,
					                    layer.startVertex,
					                    layer.nrofVertices);

					GLES20.glDisableVertexAttribArray(gPolygonvPositionHandle);
				}
			}

			if (drawn != null) {
				GLES20.glColorMask(true, true, true, true);
				GLES20.glDepthMask(true);

				// Draw with the color to fill them
				GLES20.glUseProgram(gPolygonFillProgram);
				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
				GLES20.glUniform4f(gPolygonFillColorHandle,
				                   1 + drawn.rgba[0] / 255.0f,
				                   1 + drawn.rgba[1] / 255.0f,
				                   1 + drawn.rgba[2] / 255.0f, 1);

				fullscreenCoordsBuffer.position(0);
				GLES20.glVertexAttribPointer(gPolygonFillvPositionHandle,
				                             2, GLES20.GL_FLOAT, false, 0,
				                             fullscreenCoordsBuffer);

				GLES20.glEnableVertexAttribArray(gPolygonFillvPositionHandle);

				GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 1);
				GLES20.glStencilOp(GLES20.GL_ZERO, GLES20.GL_ZERO,
				                   GLES20.GL_ZERO);

				GLES20.glEnable(GLES20.GL_DEPTH_TEST);
				GLES20.glEnable(GLES20.GL_CULL_FACE);
				GLES20.glEnable(GLES20.GL_BLEND);
				
				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

				GLES20.glDisableVertexAttribArray(gPolygonFillvPositionHandle);
			}
		}

		GLES20.glDisable(GLES20.GL_STENCIL_TEST);
		GLES20.glEnable(GLES20.GL_BLEND);
		GLES20.glEnable(GLES20.GL_DEPTH_TEST);
		GLES20.glEnable(GLES20.GL_CULL_FACE);

		// Draw lines
		GLES20.glUseProgram(gLineProgram);
		GLES20.glUniform4f(gLinecPositionHandle, x, y, 0.0f, 0.0f);
		GLES20.glUniform1f(gLineScaleXHandle, z * (float) (this.height) / (float) (this.width));
		GLES20.glUniform1f(gLineScaleYHandle, z);

		for (int i = 0; i < NROF_TILES_X; i++) {
			for (int j = 0; j < NROF_TILES_Y; j++) {
				if (tiles[i][j].loading || tiles[i][j].newData)
					continue;

				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].lineVBO);
				GLES20.glEnableVertexAttribArray(gLinevPositionHandle);
				GLES20.glEnableVertexAttribArray(gLinetexPositionHandle);

				GLES20.glVertexAttribPointer(gLinevPositionHandle, 3, GLES20.GL_FLOAT, false,
				                             20, LINE_VERTICES_DATA_POS_OFFSET);

				GLES20.glVertexAttribPointer(gLinetexPositionHandle, 2, GLES20.GL_FLOAT, false,
				                             20, LINE_VERTICES_DATA_TEX_OFFSET);

				GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, tiles[i][j].colorVBO);
				GLES20.glEnableVertexAttribArray(gLineColorHandle);

				// GLES20.glEnableVertexAttribArray(gLinevPositionHandle);

				if (gles_shader) {
					// Draw outlines

					GLES20.glVertexAttribPointer(gLineColorHandle, 4, GLES20.GL_UNSIGNED_BYTE,
					                             true, 8, LINE_VERTICES_DATA_COLOR2_OFFSET);

					GLES20.glUniform1f(gLineWidthHandle, 1.0f);
					GLES20.glUniform1f(gLineHeightOffsetHandle, 0.1f);

					GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, tiles[i][j].nrofLineVertices);
				}

				// Draw fill
				GLES20.glVertexAttribPointer(gLineColorHandle, 4, GLES20.GL_UNSIGNED_BYTE, true,
				                             8, LINE_VERTICES_DATA_COLOR1_OFFSET);
				if (gles_shader)
					GLES20.glUniform1f(gLineWidthHandle, 0.7f);

				GLES20.glUniform1f(gLineHeightOffsetHandle, 1.0f);

				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, tiles[i][j].nrofLineVertices);
			}
		}

		GLES20.glDisableVertexAttribArray(gLinetexPositionHandle);
		GLES20.glDisableVertexAttribArray(gLinevPositionHandle);
		GLES20.glDisableVertexAttribArray(gLineColorHandle);
	}
}
