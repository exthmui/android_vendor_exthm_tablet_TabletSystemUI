/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.launcher3.graphics;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.Region;
import android.graphics.Region.Op;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Xml;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.icons.GraphicsUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Abstract representation of the shape of an icon shape
 */
public abstract class IconShape {

    private static IconShape sInstance = new Circle();
    private static Path sShapePath;

    public static final int DEFAULT_PATH_SIZE = 100;

    public static IconShape getShape() {
        return sInstance;
    }

    public static Path getShapePath() {
        if (sShapePath == null) {
            Path p = new Path();
            getShape().addToPath(p, 0, 0, DEFAULT_PATH_SIZE * 0.5f);
            sShapePath = p;
        }
        return sShapePath;
    }

    public boolean enableShapeDetection(){
        return false;
    };

    public abstract void addToPath(Path path, float offsetX, float offsetY, float radius);

    /**
     * Abstract shape where the reveal animation is a derivative of a round rect animation
     */
    private static abstract class SimpleRectShape extends IconShape {

    }

    /**
     * Abstract shape which draws using {@link Path}
     */
    private static abstract class PathShape extends IconShape {

    }

    public static final class Circle extends SimpleRectShape {

        @Override
        public void addToPath(Path path, float offsetX, float offsetY, float radius) {
            path.addCircle(radius + offsetX, radius + offsetY, radius, Path.Direction.CW);
        }

        @Override
        public boolean enableShapeDetection() {
            return true;
        }
    }

    public static class RoundedSquare extends SimpleRectShape {

        /**
         * Ratio of corner radius to half size.
         */
        private final float mRadiusRatio;

        public RoundedSquare(float radiusRatio) {
            mRadiusRatio = radiusRatio;
        }

        @Override
        public void addToPath(Path path, float offsetX, float offsetY, float radius) {
            float cx = radius + offsetX;
            float cy = radius + offsetY;
            float cr = radius * mRadiusRatio;
            path.addRoundRect(cx - radius, cy - radius, cx + radius, cy + radius, cr, cr,
                    Path.Direction.CW);
        }

    }

    public static class TearDrop extends PathShape {

        /**
         * Radio of short radius to large radius, based on the shape options defined in the config.
         */
        private final float mRadiusRatio;
        private final float[] mTempRadii = new float[8];

        public TearDrop(float radiusRatio) {
            mRadiusRatio = radiusRatio;
        }

        @Override
        public void addToPath(Path p, float offsetX, float offsetY, float r1) {
            float r2 = r1 * mRadiusRatio;
            float cx = r1 + offsetX;
            float cy = r1 + offsetY;

            p.addRoundRect(cx - r1, cy - r1, cx + r1, cy + r1, getRadiiArray(r1, r2),
                    Path.Direction.CW);
        }

        private float[] getRadiiArray(float r1, float r2) {
            mTempRadii[0] = mTempRadii [1] = mTempRadii[2] = mTempRadii[3] =
                    mTempRadii[6] = mTempRadii[7] = r1;
            mTempRadii[4] = mTempRadii[5] = r2;
            return mTempRadii;
        }

    }

    public static class Squircle extends PathShape {

        /**
         * Radio of radius to circle radius, based on the shape options defined in the config.
         */
        private final float mRadiusRatio;

        public Squircle(float radiusRatio) {
            mRadiusRatio = radiusRatio;
        }

        @Override
        public void addToPath(Path p, float offsetX, float offsetY, float r) {
            float cx = r + offsetX;
            float cy = r + offsetY;
            float control = r - r * mRadiusRatio;

            p.moveTo(cx, cy - r);
            addLeftCurve(cx, cy, r, control, p);
            addRightCurve(cx, cy, r, control, p);
            addLeftCurve(cx, cy, -r, -control, p);
            addRightCurve(cx, cy, -r, -control, p);
            p.close();
        }

        private void addLeftCurve(float cx, float cy, float r, float control, Path path) {
            path.cubicTo(
                    cx - control, cy - r,
                    cx - r, cy - control,
                    cx - r, cy);
        }

        private void addRightCurve(float cx, float cy, float r, float control, Path path) {
            path.cubicTo(
                    cx - r, cy + control,
                    cx - control, cy + r,
                    cx, cy + r);
        }

    }

    /**
     * Initializes the shape which is closest to the {@link AdaptiveIconDrawable}
     */
    public static void init(Context context) {
        if (!Utilities.ATLEAST_OREO) {
            return;
        }
        pickBestShape(context);
    }

    private static IconShape getShapeDefinition(String type, float radius) {
        switch (type) {
            case "Circle":
                return new Circle();
            case "RoundedSquare":
                return new RoundedSquare(radius);
            case "TearDrop":
                return new TearDrop(radius);
            case "Squircle":
                return new Squircle(radius);
            default:
                throw new IllegalArgumentException("Invalid shape type: " + type);
        }
    }

    private static List<IconShape> getAllShapes(Context context) {
        ArrayList<IconShape> result = new ArrayList<>();
        try (XmlResourceParser parser = context.getResources().getXml(R.xml.folder_shapes)) {

            // Find the root tag
            int type;
            while ((type = parser.next()) != XmlPullParser.END_TAG
                    && type != XmlPullParser.END_DOCUMENT
                    && !"shapes".equals(parser.getName()));

            final int depth = parser.getDepth();
            int[] radiusAttr = new int[] {R.attr.folderIconRadius};

            while (((type = parser.next()) != XmlPullParser.END_TAG ||
                    parser.getDepth() > depth) && type != XmlPullParser.END_DOCUMENT) {

                if (type == XmlPullParser.START_TAG) {
                    AttributeSet attrs = Xml.asAttributeSet(parser);
                    TypedArray a = context.obtainStyledAttributes(attrs, radiusAttr);
                    IconShape shape = getShapeDefinition(parser.getName(), a.getFloat(0, 1));
                    a.recycle();

                    result.add(shape);
                }
            }
        } catch (IOException | XmlPullParserException e) {
            throw new RuntimeException(e);
        }
        return result;
    }

    @TargetApi(Build.VERSION_CODES.O)
    protected static void pickBestShape(Context context) {
        // Pick any large size
        final int size = 200;

        Region full = new Region(0, 0, size, size);
        Region iconR = new Region();
        AdaptiveIconDrawable drawable = new AdaptiveIconDrawable(
                new ColorDrawable(Color.BLACK), new ColorDrawable(Color.BLACK));
        drawable.setBounds(0, 0, size, size);
        iconR.setPath(drawable.getIconMask(), full);

        Path shapePath = new Path();
        Region shapeR = new Region();

        // Find the shape with minimum area of divergent region.
        int minArea = Integer.MAX_VALUE;
        IconShape closestShape = null;
        for (IconShape shape : getAllShapes(context)) {
            shapePath.reset();
            shape.addToPath(shapePath, 0, 0, size / 2f);
            shapeR.setPath(shapePath, full);
            shapeR.op(iconR, Op.XOR);

            int area = GraphicsUtils.getArea(shapeR);
            if (area < minArea) {
                minArea = area;
                closestShape = shape;
            }
        }

        if (closestShape != null) {
            sInstance = closestShape;
        }

        // Initialize shape properties
        drawable.setBounds(0, 0, DEFAULT_PATH_SIZE, DEFAULT_PATH_SIZE);
        sShapePath = new Path(drawable.getIconMask());
    }
}