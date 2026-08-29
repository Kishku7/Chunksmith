package com.kishku7.chunksmith.shape;

import com.kishku7.chunksmith.platform.util.Vector2;

import java.util.Optional;

public final class ShapeUtil {
    private ShapeUtil() {
    }

    /**
     * Checks if a point C is inside (to the left of) the line defined by point A and B.
     *
     * @param ax Point A x
     * @param az Point A z
     * @param bx Point B x
     * @param bz Point B z
     * @param cx Point C x
     * @param cz Point C z
     * @return Whether point C can be considered inside of line AB.
     */
    public static boolean insideLine(double ax, double az, double bx, double bz, double cx, double cz) {
        // Compute whether the point is inside the line using a cross product
        return (bx - ax) * (cz - az) > (bz - az) * (cx - ax);
    }

    /**
     * Returns the intersection point of two lines defined by two points each, if any.
     *
     * @param l1x1 Line 1 point 1 x
     * @param l1z1 Line 1 point 1 z
     * @param l1x2 Line 1 point 2 x
     * @param l1z2 Line 1 point 2 z
     * @param l2x1 Line 2 point 1 x
     * @param l2z1 Line 2 point 1 z
     * @param l2x2 Line 2 point 2 x
     * @param l2z2 Line 2 point 2 z
     * @return An optional containing the intersection point, or empty if no intersection.
     */
    public static Optional<Vector2> intersection(double l1x1, double l1z1, double l1x2, double l1z2, double l2x1, double l2z1, double l2x2, double l2z2) {
        double a1 = l1z2 - l1z1;
        double a2 = l2z2 - l2z1;
        double b1 = l1x1 - l1x2;
        double b2 = l2x1 - l2x2;
        double determinant = a1 * b2 - a2 * b1;
        if (determinant == 0) {
            return Optional.empty();
        } else {
            double c1 = a1 * l1x1 + b1 * l1z1;
            double c2 = a2 * l2x1 + b2 * l2z1;
            double x = (b2 * c1 - b1 * c2) / determinant;
            double z = (a1 * c2 - a2 * c1) / determinant;
            return Optional.of(Vector2.of(x, z));
        }
    }

    /**
     * Returns the point on the perimeter of an ellipse,
     * defined by its center and radii, that corresponds to a
     * specific angle.
     *
     * @param centerX Ellipse center x
     * @param centerZ Ellipse center z
     * @param radiusX Ellipse radius x
     * @param radiusZ Ellipse radius z
     * @param angle   Angle in radians
     * @return The point on the ellipse.
     */
    public static Vector2 pointOnEllipse(double centerX, double centerZ, double radiusX, double radiusZ, double angle) {
        double x = centerX + radiusX * Math.cos(angle);
        double z = centerZ + radiusZ * Math.sin(angle);
        return Vector2.of(x, z);
    }

    /**
     * Returns the closest point on a line from a given position.
     *
     * @param posX Position x
     * @param posZ Position z
     * @param p1x Line point 1 x
     * @param p1z Line point 1 z
     * @param p2x Line point 2 x
     * @param p2z Line point 2 z
     * @return The closest point to the position on the line.
     */
    public static Vector2 closestPointOnLine(double posX, double posZ, double p1x, double p1z, double p2x, double p2z) {
        double dx = p2x - p1x;
        double dz = p2z - p1z;
        double perpendicularSlope = -dx / dz;
        double p3x, p3z;
        if (Double.isInfinite(perpendicularSlope)) {
            p3x = posX;
            p3z = posZ + 1;
        } else {
            p3x = posX + 1;
            p3z = posZ + perpendicularSlope;
        }
        return ShapeUtil.intersection(p1x, p1z, p2x, p2z, posX, posZ, p3x, p3z).orElseThrow(IllegalStateException::new);
    }

    /**
     * Returns the distance between 2 points.
     *
     * @param p1x Point 1 x
     * @param p1z Point 1 z
     * @param p2x Point 2 x
     * @param p2z Point 2 z
     * @return The distance between the 2 points.
     */
    public static double distanceBetweenPoints(double p1x, double p1z, double p2x, double p2z) {
        return Math.sqrt(Math.pow(p1x - p2x, 2) + Math.pow(p1z - p2z, 2));
    }
}
