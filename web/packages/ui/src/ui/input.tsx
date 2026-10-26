import * as React from "react"

import { cn } from "../lib/utils"

const Input = React.forwardRef<HTMLInputElement, React.ComponentProps<"input">>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          // `w-full`, not `w-auto`. This shipped as `w-auto`, which makes an input fall back to the
          // browser's intrinsic default of roughly twenty characters — about 204px — no matter how
          // wide its container is. The visible symptom was a products search field whose
          // placeholder read "Search products by narr", inside a form that was 726px wide. Every
          // input in both apps that did not pass its own width was affected; most forms just looked
          // cramped rather than obviously broken, which is why it survived this long. A caller that
          // genuinely wants an intrinsic width can still pass `w-auto`.
          "flex h-9 w-full rounded-md border border-input bg-transparent px-3 py-1 text-base shadow-sm transition-colors file:border-0 file:bg-transparent file:text-sm file:font-medium file:text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50 md:text-sm",
          className
        )}
        ref={ref}
        {...props}
      />
    )
  }
)
Input.displayName = "Input"

export { Input }
