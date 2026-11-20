import { render } from "@testing-library/react"
import { describe, expect, it } from "vitest"

import { Snippet } from "./snippet"

describe("Snippet", () => {
  it("renders matches as <mark> elements and everything else as text", () => {
    const { container } = render(<Snippet html="the word <mark>zebra</mark> appears <mark>twice</mark>" />)
    const marks = container.querySelectorAll("mark")
    expect(marks).toHaveLength(2)
    expect(marks[0].textContent).toBe("zebra")
    expect(container.textContent).toBe("the word zebra appears twice")
  })

  it("decodes the server's escaping into text, never into markup", () => {
    const { container } = render(
      <Snippet html="Tom &amp; Jerry &lt;script&gt;alert(1)&lt;&#x2F;script&gt; <mark>zebra</mark>" />
    )
    expect(container.textContent).toBe("Tom & Jerry <script>alert(1)</script> zebra")
    expect(container.querySelector("script")).toBeNull()
    expect(container.querySelectorAll("mark")).toHaveLength(1)
  })

  it("markup that arrives unescaped is still only text", () => {
    const { container } = render(<Snippet html={'<img src=x onerror=alert(1)> <b>bold</b> <mark>hit</mark>'} />)
    expect(container.querySelector("img")).toBeNull()
    expect(container.querySelector("b")).toBeNull()
    expect(container.textContent).toContain("<img src=x onerror=alert(1)>")
    expect(container.querySelectorAll("mark")).toHaveLength(1)
  })

  it("a marked match that itself contains escaped markup stays text", () => {
    const { container } = render(<Snippet html="<mark>&lt;b&gt;x&lt;/b&gt;</mark>" />)
    expect(container.querySelector("b")).toBeNull()
    expect(container.querySelector("mark")?.textContent).toBe("<b>x</b>")
  })

  it("plain text and empty strings are fine", () => {
    expect(render(<Snippet html="" />).container.textContent).toBe("")
    expect(render(<Snippet html="just words" />).container.querySelectorAll("mark")).toHaveLength(0)
  })
})
