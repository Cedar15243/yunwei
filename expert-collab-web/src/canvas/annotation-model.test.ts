import { describe, expect, it } from "vitest";
import { AnnotationModel } from "./annotation-model";

describe("AnnotationModel", () => {
  it("builds an annotation from normalized point batches", () => {
    const model = new AnnotationModel();

    model.begin({ id: "a1", authorId: "expert-wang", tool: "pen", point: { x: 0.1, y: 0.2 } });
    model.append("a1", [{ x: 0.2, y: 0.3 }, { x: 0.3, y: 0.4 }]);
    model.complete("a1");

    expect(model.list()).toEqual([expect.objectContaining({
      id: "a1",
      complete: true,
      points: [{ x: 0.1, y: 0.2 }, { x: 0.2, y: 0.3 }, { x: 0.3, y: 0.4 }],
    })]);
  });

  it("undoes only the latest completed object owned by that expert", () => {
    const model = new AnnotationModel();
    model.begin({ id: "a1", authorId: "expert-wang", tool: "arrow", point: { x: 0.1, y: 0.1 } });
    model.append("a1", [{ x: 0.2, y: 0.2 }]);
    model.complete("a1");
    model.begin({ id: "a2", authorId: "expert-liu", tool: "circle", point: { x: 0.4, y: 0.4 } });
    model.append("a2", [{ x: 0.6, y: 0.6 }]);
    model.complete("a2");

    expect(model.undo("expert-wang")).toBe("a1");
    expect(model.list().map((item) => item.id)).toEqual(["a2"]);
  });

  it("rejects coordinates outside zero to one", () => {
    const model = new AnnotationModel();

    expect(() => model.begin({
      id: "a1",
      authorId: "expert-wang",
      tool: "pen",
      point: { x: 1.1, y: 0.5 },
    })).toThrow("normalized coordinate");
  });
});
