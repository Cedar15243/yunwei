import type { Point } from "./video-coordinates";

export type AnnotationTool = "pen" | "arrow" | "circle";

export interface AnnotationObject {
  id: string;
  authorId: string;
  tool: AnnotationTool;
  points: Point[];
  complete: boolean;
}

interface BeginAnnotation {
  id: string;
  authorId: string;
  tool: AnnotationTool;
  point: Point;
}

function validatePoint(point: Point): void {
  if (
    !Number.isFinite(point.x) || point.x < 0 || point.x > 1
    || !Number.isFinite(point.y) || point.y < 0 || point.y > 1
  ) {
    throw new Error("annotation point must use a normalized coordinate between 0 and 1");
  }
}

export class AnnotationModel {
  private readonly objects = new Map<string, AnnotationObject>();

  begin(input: BeginAnnotation): void {
    validatePoint(input.point);
    if (this.objects.has(input.id)) {
      return;
    }
    this.objects.set(input.id, {
      id: input.id,
      authorId: input.authorId,
      tool: input.tool,
      points: [input.point],
      complete: false,
    });
  }

  append(id: string, points: Point[]): void {
    const annotation = this.objects.get(id);
    if (!annotation || annotation.complete) {
      return;
    }
    for (const point of points) {
      validatePoint(point);
    }
    if (annotation.tool === "pen") {
      annotation.points.push(...points);
      return;
    }
    const lastPoint = points.at(-1);
    if (lastPoint) {
      annotation.points.splice(1, annotation.points.length - 1, lastPoint);
    }
  }

  complete(id: string): void {
    const annotation = this.objects.get(id);
    if (annotation) {
      annotation.complete = true;
    }
  }

  remove(id: string): boolean {
    return this.objects.delete(id);
  }

  undo(authorId: string): string | null {
    const owned = [...this.objects.values()].filter((item) => item.authorId === authorId && item.complete);
    const latest = owned.at(-1);
    if (!latest) {
      return null;
    }
    this.objects.delete(latest.id);
    return latest.id;
  }

  clear(): void {
    this.objects.clear();
  }

  list(): AnnotationObject[] {
    return [...this.objects.values()].map((item) => ({ ...item, points: [...item.points] }));
  }
}
