/*
 * Copyright 2012 osmdroid authors: Viesturs Zarins, Martin Pearman
 * Copyright 2012 Hannes Janetzek
 *
 * This file is part of the OpenScienceMap project (http://www.opensciencemap.org).
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.oscim.layers;

import java.util.ArrayList;
import java.util.List;

import org.oscim.backend.canvas.Paint.Cap;
import org.oscim.core.GeoPoint;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.MapPosition;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Tile;
import org.oscim.map.Map;
import org.oscim.renderer.ElementRenderer;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.elements.ElementLayers;
import org.oscim.renderer.elements.LineLayer;
import org.oscim.theme.styles.Line;
import org.oscim.utils.FastMath;
import org.oscim.utils.async.SimpleWorker;
import org.oscim.utils.geom.LineClipper;

/** This class draws a path line in given color. */
public class PathLayer extends Layer {

	/** Stores points, converted to the map projection. */
	protected final ArrayList<GeoPoint> mPoints;
	protected boolean mUpdatePoints;

	/** Line style */
	Line mLineStyle;

	final Worker mWorker;

	public PathLayer(Map map, int lineColor, float lineWidth) {
		super(map);
		mWorker = new Worker(map);
		mLineStyle = new Line(lineColor, lineWidth, Cap.BUTT);
		mRenderer = new RenderPath();
		mPoints = new ArrayList<GeoPoint>();
	}

	public PathLayer(Map map, int lineColor) {
		this(map, lineColor, 2);
	}

	public void clearPath() {
		if (mPoints.isEmpty())
			return;

		synchronized (mPoints) {
			mPoints.clear();
			mUpdatePoints = true;
		}
	}

	public void setPoints(List<GeoPoint> pts) {
		synchronized (mPoints) {
			mPoints.clear();
			mPoints.addAll(pts);
			mUpdatePoints = true;
		}
	}

	public void addPoint(GeoPoint pt) {
		synchronized (mPoints) {
			mPoints.add(pt);
			mUpdatePoints = true;
		}
	}

	public void addPoint(int latitudeE6, int longitudeE6) {
		synchronized (mPoints) {
			mPoints.add(new GeoPoint(latitudeE6, longitudeE6));
			mUpdatePoints = true;
		}
	}

	public List<GeoPoint> getPoints() {
		return mPoints;
	}

	/**
	 * FIXME To be removed
	 * 
	 * @deprecated
	 * 
	 */
	public void setGeom(GeometryBuffer geom) {
		mGeom = geom;
	}

	GeometryBuffer mGeom;

	/**
	 * Draw a great circle. Calculate a point for every 100km along the path.
	 * 
	 * @param startPoint
	 *            start point of the great circle
	 * @param endPoint
	 *            end point of the great circle
	 */
	public void addGreatCircle(GeoPoint startPoint, GeoPoint endPoint) {
		synchronized (mPoints) {

			// get the great circle path length in meters
			int length = startPoint.distanceTo(endPoint);

			// add one point for every 100kms of the great circle path
			int numberOfPoints = length / 100000;

			addGreatCircle(startPoint, endPoint, numberOfPoints);
		}
	}

	/**
	 * Draw a great circle.
	 * 
	 * @param startPoint
	 *            start point of the great circle
	 * @param endPoint
	 *            end point of the great circle
	 * @param numberOfPoints
	 *            number of points to calculate along the path
	 */
	public void addGreatCircle(GeoPoint startPoint, GeoPoint endPoint,
	        final int numberOfPoints) {
		// adapted from page
		// http://compastic.blogspot.co.uk/2011/07/how-to-draw-great-circle-on-map-in.html
		// which was adapted from page http://maps.forum.nu/gm_flight_path.html

		// convert to radians
		double lat1 = startPoint.getLatitude() * Math.PI / 180;
		double lon1 = startPoint.getLongitude() * Math.PI / 180;
		double lat2 = endPoint.getLatitude() * Math.PI / 180;
		double lon2 = endPoint.getLongitude() * Math.PI / 180;

		double d = 2 * Math.asin(Math.sqrt(Math.pow(Math.sin((lat1 - lat2) / 2), 2)
		        + Math.cos(lat1) * Math.cos(lat2)
		        * Math.pow(Math.sin((lon1 - lon2) / 2), 2)));
		double bearing = Math.atan2(
		                            Math.sin(lon1 - lon2) * Math.cos(lat2),
		                            Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1)
		                                    * Math.cos(lat2)
		                                    * Math.cos(lon1 - lon2))
		        / -(Math.PI / 180);
		bearing = bearing < 0 ? 360 + bearing : bearing;

		for (int i = 0, j = numberOfPoints + 1; i < j; i++) {
			double f = 1.0 / numberOfPoints * i;
			double A = Math.sin((1 - f) * d) / Math.sin(d);
			double B = Math.sin(f * d) / Math.sin(d);
			double x = A * Math.cos(lat1) * Math.cos(lon1) + B * Math.cos(lat2)
			        * Math.cos(lon2);
			double y = A * Math.cos(lat1) * Math.sin(lon1) + B * Math.cos(lat2)
			        * Math.sin(lon2);
			double z = A * Math.sin(lat1) + B * Math.sin(lat2);

			double latN = Math.atan2(z, Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2)));
			double lonN = Math.atan2(y, x);
			addPoint((int) (latN / (Math.PI / 180) * 1E6), (int) (lonN / (Math.PI / 180) * 1E6));
		}
	}

	/***
	 * everything below runs on GL- and Worker-Thread
	 ***/
	final class RenderPath extends ElementRenderer {

		public RenderPath() {

			layers.addLineLayer(0, mLineStyle);
		}

		private int mCurX = -1;
		private int mCurY = -1;
		private int mCurZ = -1;

		@Override
		public synchronized void update(GLViewport v) {
			int tz = 1 << v.pos.zoomLevel;
			int tx = (int) (v.pos.x * tz);
			int ty = (int) (v.pos.y * tz);

			// update layers when map moved by at least one tile
			if ((tx != mCurX || ty != mCurY || tz != mCurZ) || mUpdatePoints) {
				mWorker.submit(mUpdatePoints ? 0 : 100);
				mCurX = tx;
				mCurY = ty;
				mCurZ = tz;
			}

			Task t = mWorker.poll();
			if (t == null)
				return;

			// keep position to render relative to current state
			mMapPosition.copy(t.pos);

			// compile new layers
			layers.setBaseLayers(t.layer.getBaseLayers());
			compile();
		}
	}

	final class Task {
		ElementLayers layer = new ElementLayers();
		MapPosition pos = new MapPosition();
	}

	final class Worker extends SimpleWorker<Task> {

		// limit coords
		private final int max = 2048;

		public Worker(Map map) {
			super(map, 0, new Task(), new Task());
			mClipper = new LineClipper(-max, -max, max, max, true);
			mPPoints = new float[0];
		}

		private static final int MIN_DIST = 3;

		// pre-projected points
		private double[] mPreprojected = new double[2];

		// projected points
		private float[] mPPoints;
		private final LineClipper mClipper;
		private int mNumPoints;

		@Override
		public boolean doWork(Task task) {

			int size = mNumPoints;

			if (mUpdatePoints) {
				synchronized (mPoints) {

					mUpdatePoints = false;
					mNumPoints = size = mPoints.size();

					ArrayList<GeoPoint> geopoints = mPoints;
					double[] points = mPreprojected;

					if (size * 2 >= points.length) {
						points = mPreprojected = new double[size * 2];
						mPPoints = new float[size * 2];
					}

					for (int i = 0; i < size; i++)
						MercatorProjection.project(geopoints.get(i), points, i);
				}
			} else if (mGeom != null) {
				GeometryBuffer geom = mGeom;
				mGeom = null;
				size = geom.index[0];

				double[] points = mPreprojected;

				if (size > points.length) {
					points = mPreprojected = new double[size * 2];
					mPPoints = new float[size * 2];
				}

				for (int i = 0; i < size; i += 2)
					MercatorProjection.project(geom.points[i + 1],
					                           geom.points[i], points,
					                           i >> 1);
				mNumPoints = size = size >> 1;

			}
			if (size == 0) {
				if (task.layer.getBaseLayers() != null) {
					task.layer.clear();
					mMap.render();
				}
				return true;
			}

			ElementLayers layers = task.layer;

			LineLayer ll = layers.getLineLayer(0);
			ll.line = mLineStyle;
			ll.width = ll.line.width;

			mMap.getMapPosition(task.pos);

			int zoomlevel = task.pos.zoomLevel;
			task.pos.scale = 1 << zoomlevel;

			double mx = task.pos.x;
			double my = task.pos.y;
			double scale = Tile.SIZE * task.pos.scale;

			// flip around dateline
			int flip = 0;
			int maxx = Tile.SIZE << (zoomlevel - 1);

			int x = (int) ((mPreprojected[0] - mx) * scale);
			int y = (int) ((mPreprojected[1] - my) * scale);

			if (x > maxx) {
				x -= (maxx * 2);
				flip = -1;
			} else if (x < -maxx) {
				x += (maxx * 2);
				flip = 1;
			}

			mClipper.clipStart(x, y);

			float[] projected = mPPoints;
			int i = addPoint(projected, 0, x, y);

			float prevX = x;
			float prevY = y;

			for (int j = 2; j < size * 2; j += 2) {
				x = (int) ((mPreprojected[j + 0] - mx) * scale);
				y = (int) ((mPreprojected[j + 1] - my) * scale);

				int flipDirection = 0;
				if (x > maxx) {
					x -= maxx * 2;
					flipDirection = -1;
				} else if (x < -maxx) {
					x += maxx * 2;
					flipDirection = 1;
				}

				if (flip != flipDirection) {
					flip = flipDirection;
					if (i > 2)
						ll.addLine(projected, i, false);

					mClipper.clipStart(x, y);
					i = addPoint(projected, 0, x, y);
					continue;
				}

				int clip = mClipper.clipNext(x, y);
				if (clip < 1) {
					if (i > 2)
						ll.addLine(projected, i, false);

					if (clip < 0) {
						// add line segment
						ll.addLine(mClipper.out, 4, false);
						prevX = mClipper.out[2];
						prevY = mClipper.out[3];
					}
					i = 0;
					continue;
				}

				float dx = x - prevX;
				float dy = y - prevY;
				if ((i == 0) || FastMath.absMaxCmp(dx, dy, MIN_DIST)) {
					projected[i++] = prevX = x;
					projected[i++] = prevY = y;
				}
			}
			if (i > 2)
				ll.addLine(projected, i, false);

			// trigger redraw to let renderer fetch the result.
			mMap.render();

			return true;
		}

		@Override
		public void cleanup(Task task) {
			task.layer.clear();
		}

		private int addPoint(float[] points, int i, int x, int y) {
			points[i++] = x;
			points[i++] = y;
			return i;
		}
	}
}
