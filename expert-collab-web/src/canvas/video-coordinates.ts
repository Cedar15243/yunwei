export interface Point {
  x: number;
  y: number;
}

export interface Rectangle {
  x: number;
  y: number;
  width: number;
  height: number;
}

export function normalizePoint(point: Point, rectangle: Rectangle): Point | null {
  if (rectangle.width <= 0 || rectangle.height <= 0) {
    return null;
  }
  if (
    point.x < rectangle.x
    || point.x > rectangle.x + rectangle.width
    || point.y < rectangle.y
    || point.y > rectangle.y + rectangle.height
  ) {
    return null;
  }
  return {
    x: (point.x - rectangle.x) / rectangle.width,
    y: (point.y - rectangle.y) / rectangle.height,
  };
}

export function denormalizePoint(point: Point, rectangle: Rectangle): Point {
  return {
    x: rectangle.x + point.x * rectangle.width,
    y: rectangle.y + point.y * rectangle.height,
  };
}
