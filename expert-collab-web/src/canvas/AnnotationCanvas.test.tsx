import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AnnotationCanvas, type AnnotationTransport } from "./AnnotationCanvas";

describe("AnnotationCanvas", () => {
  it("does not create or send annotations for an observer", () => {
    const transport: AnnotationTransport = {
      send: vi.fn(),
      subscribe: () => () => undefined,
    };
    render(
      <AnnotationCanvas
        authorId="expert-liu"
        editable={false}
        sessionId="session-1"
        tool="pen"
        transport={transport}
      />,
    );
    const canvas = screen.getByLabelText("实时标注画布");

    fireEvent.pointerDown(canvas, { clientX: 10, clientY: 10, pointerId: 1 });
    fireEvent.pointerMove(canvas, { clientX: 20, clientY: 20, pointerId: 1 });
    fireEvent.pointerUp(canvas, { clientX: 20, clientY: 20, pointerId: 1 });

    expect(transport.send).not.toHaveBeenCalled();
  });
});
