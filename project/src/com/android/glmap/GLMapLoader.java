package com.android.glmap;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;

import android.util.FloatMath;
import android.util.Log;

class GLMapLoader {
	private final boolean DEBUG = false;

	private static final String TAG = "GLMapLoader";

	private static final String tiledir = "/sdcard/GLMap/tiles/";

	private static final int LINE_DATA_SIZE = 28;
	private static final int POLYGON_DATA_SIZE = 8;
	private static final int HEADER_SIZE = 8;
	private static final int VERTEX_LINE_FLOATS = 5;
	private static final int VERTEX_COLOR_BYTES = 8;
	private static final int POLY_VERTEX_SIZE = 8;
	private ByteBuffer fileBuffer;

	private int coordPos;
	private int colorPos;

	private float[] pointArray;
	private float[] coords = new float[1];
	private byte[] colors = new byte[1];

	private void addVertex(float[] floats, byte[] color) {
		System.arraycopy(floats, 0, coords, coordPos, 5);
		coordPos += VERTEX_LINE_FLOATS;
		System.arraycopy(color, 0, colors, colorPos, 8);
		colorPos += VERTEX_COLOR_BYTES;
	}

	private int unpackLinesToPolygons(int nrofLines) {
		int i, j, k;
		int n = 0;
		int ind = 0;

		float a, x, y, nextX, nextY, prevX, prevY, ux, uy, vx, vy, wx, wy;
		final float[] coord = new float[5];
		byte[] color = new byte[8];

		for (i = 0; i < nrofLines; i++) {
			int length = fileBuffer.getInt();
			float width = fileBuffer.getFloat();
			float z = fileBuffer.getFloat(); // height
			int outlineColor = fileBuffer.getInt();
			int fillColor = fileBuffer.getInt();
			boolean bridge = fileBuffer.getInt() != 0;
			boolean tunnel = fileBuffer.getInt() != 0;

			for (k = 0; k < 4; k++) {
				color[k + 4] = (byte) (outlineColor >> (k * 8));
				color[k] = (byte) (fillColor >> (k * 8));
			}
			if (bridge) {
				// Add an outline to all bridges
				color[0 + 4] = (byte) 144;
				color[1 + 4] = (byte) 144;
				color[2 + 4] = (byte) 144;
				color[3 + 4] = (byte) 255;
			}

			x = pointArray[n];
			y = pointArray[n + 1];
			n += 2;
			nextX = pointArray[n];
			nextY = pointArray[n + 1];
			n += 2;

			// Calculate triangle corners for the given width
			vx = nextX - x;
			vy = nextY - y;
			a = FloatMath.sqrt(vx * vx + vy * vy);
			vx = vx / a;
			vy = vy / a;

			ux = -vy;
			uy = vx;

			float shrink = 0.2f;

			float uxw = ux * width;
			float uyw = uy * width;
			float sxw = vx * width * shrink;
			float syw = vy * width * shrink;
			float vxw = vx * width * (1 - shrink);
			float vyw = vy * width * (1 - shrink);

			coord[2] = z;

			if (!bridge && !tunnel) {
				// Add the first point twice to be able to draw with
				// GL_TRIANGLE_STRIP
				coord[0] = x + uxw - vxw;
				coord[1] = y + uyw - vyw;
				coord[3] = -1.0f;
				coord[4] = 1.0f;
				addVertex(coord, color);
				addVertex(coord, color);

				coord[0] = x - uxw - vxw;
				coord[1] = y - uyw - vyw;
				coord[3] = 1.0f;
				coord[4] = 1.0f;
				addVertex(coord, color);

				// Start of line
				coord[0] = x + uxw + sxw;
				coord[1] = y + uyw + syw;
				coord[3] = -1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				coord[0] = x - uxw + sxw;
				coord[1] = y - uyw + syw;
				coord[3] = 1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				ind += 5;

			} else {
				// Add the first point twice to be able to draw with
				// GL_TRIANGLE_STRIP
				coord[0] = x + uxw;
				coord[1] = y + uyw;
				coord[3] = -1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);
				addVertex(coord, color);

				coord[0] = x - uxw;
				coord[1] = y - uyw;
				coord[3] = 1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				ind += 3;
			}

			prevX = x;
			prevY = y;
			x = nextX;
			y = nextY;

			for (j = 1; j < length - 1; j++) {

				nextX = pointArray[n];
				nextY = pointArray[n + 1];
				n += 2;

				// Unit vector pointing back to previous node
				vx = prevX - x;
				vy = prevY - y;
				a = FloatMath.sqrt(vx * vx + vy * vy);
				vx = vx / a;
				vy = vy / a;

				// Unit vector pointing forward to next node
				wx = nextX - x;
				wy = nextY - y;
				a = FloatMath.sqrt(wx * wx + wy * wy);
				wx = wx / a;
				wy = wy / a;

				// Sum of these two vectors points
				ux = vx + wx;
				uy = vy + wy;
				a = -wy * ux + wx * uy;

				if (a < 0.01 && a > -0.01) {
					// Almost straight, use normal vector
					ux = -wy;
					uy = wx;
				} else {
					// Normalize u, and project normal vector onto this
					ux = ux / a;
					uy = uy / a;
				}

				uxw = ux * width;
				uyw = uy * width;

				coord[0] = x + uxw;
				coord[1] = y + uyw;
				coord[3] = -1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				coord[0] = x - uxw;
				coord[1] = y - uyw;
				coord[3] = 1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				ind += 2;

				prevX = x;
				prevY = y;
				x = nextX;
				y = nextY;
			}

			vx = prevX - x;
			vy = prevY - y;

			a = FloatMath.sqrt(vx * vx + vy * vy);
			vx = vx / a;
			vy = vy / a;

			ux = vy;
			uy = -vx;

			uxw = ux * width;
			uyw = uy * width;

			if (!bridge && !tunnel) {
				sxw = vx * width * shrink;
				syw = vy * width * shrink;
				vxw = vx * width * (1 - shrink);
				vyw = vy * width * (1 - shrink);

				coord[0] = x + uxw + sxw;
				coord[1] = y + uyw + syw;
				coord[3] = -1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				coord[0] = x - uxw + sxw;
				coord[1] = y - uyw + syw;
				coord[3] = 1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				// For rounded line edges
				coord[0] = x + uxw - vxw;
				coord[1] = y + uyw - vyw;
				coord[3] = -1.0f;
				coord[4] = -1.0f;
				addVertex(coord, color);

				// Add the last vertex twice to be able to draw with
				// GL_TRIANGLE_STRIP
				coord[0] = x - uxw - vxw;
				coord[1] = y - uyw - vyw;
				coord[3] = 1.0f;
				coord[4] = -1.0f;
				addVertex(coord, color);
				addVertex(coord, color);

				ind += 5;

			} else {
				coord[0] = x + uxw;
				coord[1] = y + uyw;
				coord[3] = -1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);

				// Add the last vertex twice to be able to draw with
				// GL_TRIANGLE_STRIP
				coord[0] = x - uxw;
				coord[1] = y - uyw;
				coord[3] = 1.0f;
				coord[4] = 0.0f;
				addVertex(coord, color);
				addVertex(coord, color);

				ind += 3;
			}
		}

		return ind;
	}

	private int unpackPolygons(GLMapTile tile, int nrofPolygons) {
		ByteBuffer buf = tile.polygonVerticesBuffer;

		int size;
		byte[] rgba = new byte[4];

		tile.polygonLayers = new ArrayList<PolygonLayer>();

		fileBuffer.position(HEADER_SIZE);

		// Scan through the polygons and set up the needed layers
		for (int i = 0; i < nrofPolygons; i++) {
			boolean found = false;

			size = fileBuffer.getInt();
			fileBuffer.get(rgba);

			int thisPolygonVertices = size + 2;

			for (PolygonLayer layer : tile.polygonLayers) {
				if (colorIsEqual(layer.rgba, rgba)) {
					layer.nrofVertices += thisPolygonVertices;
					layer.nrofPolygons++;
					found = true;
					break;
				}
			}

			if (!found) {
				PolygonLayer layer = new PolygonLayer();
				tile.polygonLayers.add(layer);

				layer.nrofVertices = thisPolygonVertices;
				layer.nrofPolygons = 1;
				for (int k = 0; k < 4; k++)
					layer.rgba[k] = rgba[k];
			}

			tile.nrofPolygonVertices += thisPolygonVertices;
		}

		int layers = tile.polygonLayers.size();
		if (layers == 0)
			return 0;

		// Set up start indices
		PolygonLayer layer = tile.polygonLayers.get(0);
		layer.startVertex = 0;
		layer.polygonIndex = new int[layer.nrofPolygons * 2];

		for (int l = 1; l < layers; l++) {

			tile.polygonLayers.get(l).startVertex =
			   layer.startVertex + layer.nrofVertices;

			layer = tile.polygonLayers.get(l);

			layer.polygonIndex = new int[layer.nrofPolygons * 2];
		}

		// Load all the polygon vertices into the correct layer
		float originX = pointArray[0];
		float originY = pointArray[1];

		int tgtIdx = 0;

		for (int l = 0; l < layers; l++) {
			layer = tile.polygonLayers.get(l);
			int srcIdx = 0;
			int p = 0;

			fileBuffer.position(HEADER_SIZE);

			for (int i = 0; i < nrofPolygons; i++) {
				size = fileBuffer.getInt();
				fileBuffer.get(rgba);

				if (colorIsEqual(layer.rgba, rgba)) {

					buf.putFloat(originX);
					buf.putFloat(originY);
					tgtIdx++;

					layer.polygonIndex[p++] = tgtIdx;
					layer.polygonIndex[p++] = size + 1;

					float startX = pointArray[srcIdx];
					float startY = pointArray[srcIdx + 1];

					for (int j = 0; j < size * 2; j += 2) {
						buf.putFloat(pointArray[srcIdx + j]);
						buf.putFloat(pointArray[srcIdx + j + 1]);
						tgtIdx++;
					}

					buf.putFloat(startX);
					buf.putFloat(startY);
					tgtIdx++;
				}

				srcIdx += size * 2;
			}
		}

		return tgtIdx;
	}

	static boolean colorIsEqual(byte rgba1[], byte rgba2[]) {
		int k;

		for (k = 0; k < 4; k++) {
			if (rgba1[k] != rgba2[k]) {
				return false;
			}
		}

		return true;
	}

	private static RandomAccessFile inputFile;
	private static FileChannel fileChannel;

	private boolean openFile(String fileName) {
		try {
			File file = new File(fileName);

			if (!file.exists()) {
				Log.e(TAG, "file does not exist: " + fileName);
				return false;
			} else if (!file.isFile()) {
				Log.e(TAG, "not a file: " + fileName);
				return false;
			} else if (!file.canRead()) {
				Log.e(TAG, "cannot read file: " + fileName);
				return false;
			}
			inputFile = new RandomAccessFile(file, "r");

			fileChannel = inputFile.getChannel();

			// mmap file
			fileBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
			      .order(ByteOrder.nativeOrder());

		} catch (IOException e) {
			Log.e(TAG, "openFile: " + e);
			return false;
		}
		return true;
	}

	private boolean closeFile() {
		try {
			fileChannel.close();
			inputFile.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return true;
	}

	public synchronized boolean loadMapTile(String tileName, GLMapTile tile) {
		// Load map data from files

		// Read in line data
		String fileName = tiledir + tileName + ".line";
		if (DEBUG)
			Log.i(TAG, "Reading map line data from file '" + fileName + "'.");

		if (!openFile(fileName))
			return false;

		int nrofLines = fileBuffer.getInt();
		int nrofLinePoints = fileBuffer.getInt();
		if (DEBUG)
			Log.i(TAG, "Found: " + nrofLines + " lines, " + nrofLinePoints + " vertices.");

		// For each line, we get at most twice the number of points, plus one
		// extra node in the beginning and end
		int nrofLineVertices = 2 * nrofLinePoints + 6 * nrofLines;

		int size = nrofLineVertices * 4 * VERTEX_LINE_FLOATS;

		// putting each vertex on its own into ByteBuffer took too long...
		if (coords.length < nrofLineVertices * VERTEX_LINE_FLOATS) {
			coords = new float[nrofLineVertices * VERTEX_LINE_FLOATS];
			colors = new byte[nrofLineVertices * VERTEX_COLOR_BYTES];
		}

		coordPos = 0;
		colorPos = 0;

		if (DEBUG)
			Log.i(TAG, "Parsing map line data.");

		if (pointArray == null || pointArray.length < nrofLinePoints * 2)
			pointArray = new float[nrofLinePoints * 2];

		int offset = HEADER_SIZE + nrofLines * LINE_DATA_SIZE;
		fileBuffer.position(offset);
		fileBuffer.asFloatBuffer().get(pointArray, 0, nrofLinePoints * 2);

		// skip nrofLines/Points
		fileBuffer.position(HEADER_SIZE);

		tile.nrofLineVertices = unpackLinesToPolygons(nrofLines);

		size = tile.nrofLineVertices * 4 * VERTEX_LINE_FLOATS;
		if (tile.lineVerticesBufferSize < size) {
			tile.lineVerticesBufferSize = size;
			tile.lineVerticesBuffer = ByteBuffer.allocateDirect(size)
			      .order(ByteOrder.nativeOrder());

			size = nrofLineVertices * VERTEX_COLOR_BYTES;
			tile.colorVerticesBuffer = ByteBuffer.allocateDirect(size)
			      .order(ByteOrder.nativeOrder());
		}

		tile.lineVerticesBuffer.position(0);
		tile.colorVerticesBuffer.position(0);

		FloatBuffer buf = tile.lineVerticesBuffer.asFloatBuffer();
		buf.put(coords, 0, tile.nrofLineVertices * VERTEX_LINE_FLOATS);

		tile.colorVerticesBuffer.put(colors, 0, tile.nrofLineVertices * VERTEX_COLOR_BYTES);

		tile.lineVerticesBuffer.position(0);
		tile.colorVerticesBuffer.position(0);

		if (DEBUG)
			Log.i(TAG, "Finished parsing. " + tile.nrofLineVertices);

		closeFile();

		// Read in polygon data
		fileName = tiledir + tileName + ".poly";
		if (DEBUG)
			Log.i(TAG, "Reading map polygon data from file '" + fileName + "'.");

		if (!openFile(fileName))
			return false;

		int nrofPolygons = fileBuffer.getInt();
		int nrofPolygonPoints = fileBuffer.getInt();
		if (nrofPolygons == 0) {
			closeFile();
			tile.nrofPolygonVertices = 0;
			return true;
		}
		if (DEBUG)
			Log.i(TAG, "Found: " + nrofPolygons + " polygons, " + nrofPolygonPoints
			      + " vertices.");

		// first tile vertex is added to each polygon + 2*the start vertex
		int nrofPolygonVertices = nrofPolygonPoints; // + 2 * nrofPolygons;

		// buffer for drawing polygon vertices
		size = (nrofPolygonVertices + 2 * nrofPolygons) * POLY_VERTEX_SIZE;

		if (tile.polygonVerticesBufferSize < size) {
			tile.polygonVerticesBufferSize = size;
			tile.polygonVerticesBuffer = ByteBuffer.allocateDirect(size)
			      .order(ByteOrder.nativeOrder());
		}

		tile.polygonVerticesBuffer.position(0);

		// read point data
		if (pointArray.length < nrofPolygonPoints * 2)
			pointArray = new float[nrofPolygonPoints * 2];

		offset = HEADER_SIZE + nrofPolygons * POLYGON_DATA_SIZE;
		fileBuffer.position(offset);
		fileBuffer.asFloatBuffer().get(pointArray, 0, nrofPolygonPoints * 2);

		// skip nrofPolygons/Points
		fileBuffer.position(HEADER_SIZE);

		if (DEBUG)
			Log.i(TAG, "Parsing map polygon data.");

		tile.nrofPolygonVertices = unpackPolygons(tile, nrofPolygons);
		tile.polygonVerticesBuffer.position(0);
		if (DEBUG)
			Log.i(TAG, "Finished parsing. " + tile.nrofPolygonVertices);

		closeFile();

		return true;
	}
}
