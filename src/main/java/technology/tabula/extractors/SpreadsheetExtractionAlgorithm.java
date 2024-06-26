package technology.tabula.extractors;

import technology.tabula.*;

import java.awt.geom.Point2D;
import java.util.*;

/**
 * @author manuel
 *
 */
public class SpreadsheetExtractionAlgorithm implements ExtractionAlgorithm {

    private static final float MAGIC_HEURISTIC_NUMBER = 0.65f;

    /** Minimum space between two aligned consecutive HORIZONTAL rulings */
    int maxGapBetweenAlignedHorizontalRulings = Ruling.COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT * 2;

    /** Minimum space between two aligned consecutive VERTICAL rulings */
    int maxGapBetweenAlignedVerticalRulings = Ruling.COLINEAR_OR_PARALLEL_PIXEL_EXPAND_AMOUNT * 2;

    float minColumnWidth = 0f;
    float minRowHeight = 0f;

    public SpreadsheetExtractionAlgorithm() {
    }

    public SpreadsheetExtractionAlgorithm withMaxGapBetweenAlignedHorizontalRulings(
            int maxGapBetweenAlignedHorizontalRulings) {
        this.maxGapBetweenAlignedHorizontalRulings = maxGapBetweenAlignedHorizontalRulings;
        return this;
    }

    public SpreadsheetExtractionAlgorithm withMaxGapBetweenAlignedVerticalRulings(
            int maxGapBetweenAlignedVerticalRulings) {
        this.maxGapBetweenAlignedVerticalRulings = maxGapBetweenAlignedVerticalRulings;
        return this;
    }

    public SpreadsheetExtractionAlgorithm withMinColumnWidth(float minColumnWidth) {
        this.minColumnWidth = minColumnWidth;
        return this;
    }

    public SpreadsheetExtractionAlgorithm withMinRowHeight(float minRowHeight) {
        this.minRowHeight = minRowHeight;
        return this;
    }

    private static final Comparator<Point2D> Y_FIRST_POINT_COMPARATOR = (point1, point2) -> {
        int compareY = compareRounded(point1.getY(), point2.getY());
        if (compareY == 0) {
            return compareRounded(point1.getX(), point2.getX());
        }
        return compareY;
    };

    private static final Comparator<Point2D> X_FIRST_POINT_COMPARATOR = (point1, point2) -> {
        int compareX = compareRounded(point1.getX(), point2.getX());
        if (compareX == 0) {
            return compareRounded(point1.getY(), point2.getY());
        }
        return compareX;
    };

    private static int compareRounded(double d1, double d2) {
        float d1Rounded = Utils.round(d1, 2);
        float d2Rounded = Utils.round(d2, 2);

        return Float.compare(d1Rounded, d2Rounded);
    }

    @Override
    public List<Table> extract(Page page) {
        return extract(page, page.getRulings());
    }

    /**
     * Extract a list of Table from page using rulings as separators
     */
    public List<Table> extract(Page page, List<Ruling> rulings) {
        // split rulings into horizontal and vertical
        List<Ruling> horizontalR = new ArrayList<>();
        List<Ruling> verticalR = new ArrayList<>();

        for (Ruling r : rulings) {
            if (r.horizontal()) {
                horizontalR.add(r);
            } else if (r.vertical()) {
                verticalR.add(r);
            }
        }

        // Repeat rulings collapsing until stability is reached
        int count = 0;
        int horizontalExpandAmount = maxGapBetweenAlignedHorizontalRulings / 2;
        int verticalExpandAmount = maxGapBetweenAlignedVerticalRulings / 2;
        do {
            count = horizontalR.size();
            horizontalR = Ruling.collapseOrientedRulings(horizontalR, horizontalExpandAmount, verticalExpandAmount,
                    minRowHeight);
        } while (count != horizontalR.size());

        do {
            count = verticalR.size();
            verticalR = Ruling.collapseOrientedRulings(verticalR, verticalExpandAmount, horizontalExpandAmount,
                    minColumnWidth);
        } while (count != verticalR.size());

        List<Cell> cells = findCells(horizontalR, verticalR, horizontalExpandAmount, verticalExpandAmount);
        List<Rectangle> spreadsheetAreas = findSpreadsheetsFromCells(cells);

        List<Table> spreadsheets = new ArrayList<>();
        for (Rectangle area : spreadsheetAreas) {

            List<Cell> overlappingCells = new ArrayList<>();
            for (Cell c : cells) {
                if (c.intersects(area)) {
                    // Extend the cell area by 1% right to catch any trailing letter overlapped by
                    // the cell border
                    Rectangle extendedCellArea = new Rectangle(c.getTop(), c.getLeft(), (float) (c.getWidth() * 1.01),
                            (float) c.getHeight());
                    List<TextChunk> textChunks = TextElement.mergeWords(page.getText(extendedCellArea));
                    c.setTextElements(textChunks);
                    overlappingCells.add(c);
                }
            }

            List<Ruling> horizontalOverlappingRulings = new ArrayList<>();
            for (Ruling hr : horizontalR) {
                if (area.intersectsLine(hr)) {
                    horizontalOverlappingRulings.add(hr);
                }
            }
            List<Ruling> verticalOverlappingRulings = new ArrayList<>();
            for (Ruling vr : verticalR) {
                if (area.intersectsLine(vr)) {
                    verticalOverlappingRulings.add(vr);
                }
            }

            TableWithRulingLines t = new TableWithRulingLines(area, overlappingCells, horizontalOverlappingRulings,
                    verticalOverlappingRulings, this, page.getPageNumber());
            spreadsheets.add(t);
        }
        Utils.sort(spreadsheets, Rectangle.ILL_DEFINED_ORDER);
        return spreadsheets;
    }

    public boolean isTabular(Page page) {

        // if there's no text at all on the page, it's not a table
        // (we won't be able to do anything with it though)
        if (page.getText().isEmpty()) {
            return false;
        }

        // get minimal region of page that contains every character (in effect,
        // removes white "margins")
        Page minimalRegion = page.getArea(Utils.bounds(page.getText()));

        List<? extends Table> tables = new SpreadsheetExtractionAlgorithm().extract(minimalRegion);
        if (tables.isEmpty()) {
            return false;
        }
        Table table = tables.get(0);
        int rowsDefinedByLines = table.getRowCount();
        int colsDefinedByLines = table.getColCount();

        tables = new BasicExtractionAlgorithm().extract(minimalRegion);
        if (tables.isEmpty()) {
            return false;
        }
        table = tables.get(0);
        int rowsDefinedWithoutLines = table.getRowCount();
        int colsDefinedWithoutLines = table.getColCount();

        float ratio = (((float) colsDefinedByLines / colsDefinedWithoutLines) +
                ((float) rowsDefinedByLines / rowsDefinedWithoutLines)) / 2.0f;

        return ratio > MAGIC_HEURISTIC_NUMBER && ratio < (1 / MAGIC_HEURISTIC_NUMBER);
    }

    public static List<Cell> findCells(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines) {
        return findCells(horizontalRulingLines, verticalRulingLines, Ruling.PERPENDICULAR_PIXEL_EXPAND_AMOUNT,
                Ruling.PERPENDICULAR_PIXEL_EXPAND_AMOUNT);
    }

    public static List<Cell> findCells(List<Ruling> horizontalRulingLines, List<Ruling> verticalRulingLines,
            int horizontalExpandAmount, int verticalExpandAmount) {
        List<Cell> cellsFound = new ArrayList<>();
        Map<Point2D, Ruling[]> intersectionPoints = Ruling.findIntersections(horizontalRulingLines, verticalRulingLines,
                horizontalExpandAmount, verticalExpandAmount);
        List<Point2D> intersectionPointsList = new ArrayList<>(intersectionPoints.keySet());
        intersectionPointsList.sort(Y_FIRST_POINT_COMPARATOR);

        for (int i = 0; i < intersectionPointsList.size(); i++) {
            Point2D topLeft = intersectionPointsList.get(i);
            Ruling[] hv = intersectionPoints.get(topLeft);

            List<Point2D> xPoints = new ArrayList<>();
            List<Point2D> yPoints = new ArrayList<>();

            for (Point2D p : intersectionPointsList.subList(i, intersectionPointsList.size())) {
                if (p.getX() == topLeft.getX() && p.getY() > topLeft.getY()) {
                    xPoints.add(p);
                }
                if (p.getY() == topLeft.getY() && p.getX() > topLeft.getX()) {
                    yPoints.add(p);
                }
            }
            outer: for (Point2D xPoint : xPoints) {

                // is there a vertical edge b/w topLeft and xPoint?
                if (!hv[1].equals(intersectionPoints.get(xPoint)[1])) {
                    continue;
                }
                for (Point2D yPoint : yPoints) {
                    // is there an horizontal edge b/w topLeft and yPoint ?
                    if (!hv[0].equals(intersectionPoints.get(yPoint)[0])) {
                        continue;
                    }
                    Point2D btmRight = new Point2D.Float((float) yPoint.getX(), (float) xPoint.getY());
                    if (intersectionPoints.containsKey(btmRight)
                            && intersectionPoints.get(btmRight)[0].equals(intersectionPoints.get(xPoint)[0])
                            && intersectionPoints.get(btmRight)[1].equals(intersectionPoints.get(yPoint)[1])) {
                        cellsFound.add(new Cell(topLeft, btmRight));
                        break outer;
                    }
                }
            }
        }

        // TODO create cells for vertical ruling lines with aligned endpoints at the
        // top/bottom of a grid
        // that aren't connected with an horizontal ruler?
        // see:
        // https://github.com/jazzido/tabula-extractor/issues/78#issuecomment-41481207

        List<Cell> missingCells = findGaps(cellsFound);
        if (!missingCells.isEmpty()) {
            cellsFound.addAll(missingCells);
            Utils.sort(cellsFound, Rectangle.ILL_DEFINED_ORDER);
        }
        return cellsFound;
    }

    public static List<Rectangle> findSpreadsheetsFromCells(List<? extends Rectangle> cells) {
        // via:
        // http://stackoverflow.com/questions/13746284/merging-multiple-adjacent-rectangles-into-one-polygon
        List<Rectangle> rectangles = new ArrayList<>();
        Set<Point2D> pointSet = new HashSet<>();
        Map<Point2D, Point2D> edgesH = new HashMap<>();
        Map<Point2D, Point2D> edgesV = new HashMap<>();
        int i = 0;

        cells = new ArrayList<>(new HashSet<>(cells));

        Utils.sort(cells, Rectangle.ILL_DEFINED_ORDER);

        for (Rectangle cell : cells) {
            for (Point2D pt : cell.getPoints()) {
                if (pointSet.contains(pt)) { // shared vertex, remove it
                    pointSet.remove(pt);
                } else {
                    pointSet.add(pt);
                }
            }
        }

        // X first sort
        List<Point2D> pointsSortX = new ArrayList<>(pointSet);
        pointsSortX.sort(X_FIRST_POINT_COMPARATOR);
        // Y first sort
        List<Point2D> pointsSortY = new ArrayList<>(pointSet);
        pointsSortY.sort(Y_FIRST_POINT_COMPARATOR);

        while (i < pointSet.size()) {
            float currY = (float) pointsSortY.get(i).getY();
            while (i < pointSet.size() && Utils.feq(pointsSortY.get(i).getY(), currY)) {
                edgesH.put(pointsSortY.get(i), pointsSortY.get(i + 1));
                edgesH.put(pointsSortY.get(i + 1), pointsSortY.get(i));
                i += 2;
            }
        }

        i = 0;
        while (i < pointSet.size()) {
            float currX = (float) pointsSortX.get(i).getX();
            while (i < pointSet.size() && Utils.feq(pointsSortX.get(i).getX(), currX)) {
                edgesV.put(pointsSortX.get(i), pointsSortX.get(i + 1));
                edgesV.put(pointsSortX.get(i + 1), pointsSortX.get(i));
                i += 2;
            }
        }

        // Get all the polygons
        List<List<PolygonVertex>> polygons = new ArrayList<>();
        Point2D nextVertex;
        while (!edgesH.isEmpty()) {
            ArrayList<PolygonVertex> polygon = new ArrayList<>();
            Point2D first = edgesH.keySet().iterator().next();
            polygon.add(new PolygonVertex(first, Direction.HORIZONTAL));
            edgesH.remove(first);

            while (true) {
                PolygonVertex curr = polygon.get(polygon.size() - 1);
                PolygonVertex lastAddedVertex;
                if (curr.direction == Direction.HORIZONTAL) {
                    nextVertex = edgesV.get(curr.point);
                    edgesV.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.VERTICAL);
                } else {
                    nextVertex = edgesH.get(curr.point);
                    edgesH.remove(curr.point);
                    lastAddedVertex = new PolygonVertex(nextVertex, Direction.HORIZONTAL);
                }
                polygon.add(lastAddedVertex);

                if (lastAddedVertex.equals(polygon.get(0))) {
                    // closed polygon
                    polygon.remove(polygon.size() - 1);
                    break;
                }
            }

            for (PolygonVertex vertex : polygon) {
                edgesH.remove(vertex.point);
                edgesV.remove(vertex.point);
            }
            polygons.add(polygon);
        }

        // calculate grid-aligned minimum area rectangles for each found polygon
        for (List<PolygonVertex> poly : polygons) {
            float top = java.lang.Float.MAX_VALUE;
            float left = java.lang.Float.MAX_VALUE;
            float bottom = java.lang.Float.MIN_VALUE;
            float right = java.lang.Float.MIN_VALUE;
            for (PolygonVertex pt : poly) {
                top = (float) Math.min(top, pt.point.getY());
                left = (float) Math.min(left, pt.point.getX());
                bottom = (float) Math.max(bottom, pt.point.getY());
                right = (float) Math.max(right, pt.point.getX());
            }
            rectangles.add(new Rectangle(top, left, right - left, bottom - top));
        }

        return rectangles;
    }

    @Override
    public String toString() {
        return "lattice";
    }

    private enum Direction {
        HORIZONTAL,
        VERTICAL
    }

    static class PolygonVertex {
        Point2D point;
        Direction direction;

        public PolygonVertex(Point2D point, Direction direction) {
            this.direction = direction;
            this.point = point;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other)
                return true;
            if (!(other instanceof PolygonVertex))
                return false;
            return this.point.equals(((PolygonVertex) other).point);
        }

        @Override
        public int hashCode() {
            return this.point.hashCode();
        }

        @Override
        public String toString() {
            return String.format("%s[point=%s,direction=%s]", this.getClass().getName(), this.point.toString(),
                    this.direction.toString());
        }
    }

    static List<Cell> findGaps(List<Cell> cells) {
        if (cells.isEmpty()) {
            return new LinkedList<>();
        }

        List<Cell> gaps = new LinkedList<>();
        float tableLeft = cells.stream().map(Cell::getLeft).min(Float::compareTo).orElse(0f);
        for (Cell cell : cells) {
            if (cell.isEmpty()) {
                continue;
            }
            Optional<RectangularTextContainer> leftNeighbour = findNeighbour(cell, cells, true);
            if (!Utils.feq(cell.getLeft(), tableLeft) && leftNeighbour.isEmpty()) {
                // Create a cell at the beginning of the row
                Cell newCell = new Cell(cell.getTop(), tableLeft, cell.getLeft() - tableLeft, (float) cell.getHeight());
                gaps.add(newCell);
            } else if (leftNeighbour.isPresent() && !Utils.feq(leftNeighbour.get().getRight(), cell.getLeft())) {
                // Create a cell between left neighbour and cell
                Cell newCell = new Cell(cell.getTop(), leftNeighbour.get().getRight(),
                        cell.getLeft() - leftNeighbour.get().getRight(), (float) cell.getHeight());
                gaps.add(newCell);
            }
        }

        return gaps;
    }

    static Optional<RectangularTextContainer> findNeighbour(Cell cell, List<Cell> cells, boolean onLeft) {
        RectangularTextContainer bestCandidate = null;
        boolean onRight = !onLeft;
        for (Cell candidate : cells) {
            if (cell != candidate && sameRow(cell, candidate)) {
                if (onLeft && cell.getLeft() >= candidate.getRight()
                        && (bestCandidate == null || candidate.getRight() > bestCandidate.getRight())) {
                    bestCandidate = candidate;
                } else if (onRight && cell.getRight() <= candidate.getLeft()
                        && (bestCandidate == null || candidate.getLeft() < bestCandidate.getLeft())) {
                    bestCandidate = candidate;
                }
            }
        }

        return Optional.ofNullable(bestCandidate);
    }

    static boolean sameRow(Cell cell, Cell candidate) {
        if (!isWithin(cell.getTop(), candidate.getBottom(), candidate.getTop()) &&
                !isWithin(cell.getBottom(), candidate.getBottom(), candidate.getTop()) &&
                !isWithin(candidate.getBottom(), cell.getBottom(), cell.getTop()) &&
                !isWithin(candidate.getBottom(), cell.getBottom(), cell.getTop())) {
            return false;
        }

        // Top and bottom cells have common borders, but are not on the same row
        return !Utils.feq(cell.getTop(), candidate.getBottom()) && !Utils.feq(cell.getBottom(), candidate.getTop());
    }

    static boolean isWithin(float x, float start, float end) {
        return start < end ? (x >= start && x <= end) : (x <= start && x >= end);
    }
}
