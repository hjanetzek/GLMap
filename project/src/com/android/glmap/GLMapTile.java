package com.android.glmap;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import android.os.AsyncTask;

public class GLMapTile {

	int x;
	int y;

	int lineVBO;
	int colorVBO;
	int polygonVBO;

	int nrofLineVertices;
	int nrofPolygonVertices;

	ArrayList<PolygonLayer> polygonLayers;

	ByteBuffer colorVerticesBuffer;
	ByteBuffer lineVerticesBuffer;
	ByteBuffer polygonVerticesBuffer;

	boolean newData;
	boolean loading;

	public AsyncTask<Void, Void, Integer> task;

}
