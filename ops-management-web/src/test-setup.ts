import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(cleanup);

class TestDOMMatrixReadOnly {
  readonly m22: number;

  constructor(transform?: string) {
    const matrix = transform?.match(/^matrix\([^,]+,[^,]+,[^,]+,([^,]+),/);
    this.m22 = matrix ? Number(matrix[1]) : 1;
  }
}

Object.defineProperty(window, "DOMMatrixReadOnly", {
  configurable: true,
  value: TestDOMMatrixReadOnly,
});

class TestResizeObserver implements ResizeObserver {
  constructor(private readonly callback: ResizeObserverCallback) {}

  observe(target: Element, _options?: ResizeObserverOptions): void {
    const isNode = target.classList.contains("react-flow__node");
    const contentRect = DOMRect.fromRect({
      height: isNode ? 58 : 600,
      width: isNode ? 164 : 900,
      x: 0,
      y: 0,
    });
    Object.defineProperties(target, {
      offsetHeight: { configurable: true, value: contentRect.height },
      offsetWidth: { configurable: true, value: contentRect.width },
    });
    Object.defineProperty(target, "getBoundingClientRect", {
      configurable: true,
      value: () => contentRect,
    });
    this.callback([
      {
        borderBoxSize: [],
        contentBoxSize: [],
        contentRect,
        devicePixelContentBoxSize: [],
        target,
      },
    ], this);
  }
  unobserve(_target: Element): void {}
  disconnect(): void {}
}

Object.defineProperty(globalThis, "ResizeObserver", {
  configurable: true,
  value: TestResizeObserver,
});
