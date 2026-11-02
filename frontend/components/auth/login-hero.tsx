/** A glossy brand-green sphere. Drawn with gradients only, so it stays crisp at any size. */
const SPHERE_BG =
  "radial-gradient(circle at 30% 24%, rgb(255 255 255 / 0.3), rgb(255 255 255 / 0) 42%), linear-gradient(150deg, #2f7557 0%, #1f4d3a 52%, #123527 100%)"

/**
 * The big overlapping spheres behind the brand panel's text, each bobbing on
 * its own slow loop (see `lg-bob` in globals.css).
 */
export function LoginSpheres() {
  return (
    <>
      <div
        className="lg-bob pointer-events-none absolute -top-28 -right-24 size-[460px] rounded-full opacity-70 shadow-[inset_-18px_-22px_60px_rgb(0_0_0/0.28)]"
        style={{ background: SPHERE_BG, animationDuration: "28s" }}
      />
      <div
        className="lg-bob pointer-events-none absolute -bottom-36 -left-28 size-[380px] rounded-full opacity-80 shadow-[inset_-14px_-18px_50px_rgb(0_0_0/0.3)]"
        style={{
          background: SPHERE_BG,
          animationDuration: "24s",
          animationDelay: "-6s",
        }}
      />
      <div
        className="lg-bob pointer-events-none absolute -bottom-14 left-[34%] size-[210px] rounded-full opacity-90 shadow-[inset_-10px_-14px_36px_rgb(0_0_0/0.3),0_24px_50px_rgb(0_0_0/0.25)]"
        style={{
          background: SPHERE_BG,
          animationDuration: "20s",
          animationDelay: "-3s",
        }}
      />
    </>
  )
}

/**
 * "WELCOME", the product name and the tagline - static, on purpose. The
 * parent panel is aria-hidden, so this is purely decorative.
 */
export function LoginHeadline({ appName }: { appName: string }) {
  return (
    <div className="text-left">
      <p className="font-heading text-[44px] leading-none font-extrabold tracking-[0.04em] xl:text-[54px] [@media(max-height:760px)]:text-[36px] [@media(max-height:760px)]:xl:text-[40px]">
        WELCOME
      </p>
      <p className="mt-4 font-heading text-lg font-semibold tracking-[0.14em] uppercase opacity-90">
        {appName}
      </p>
      <p className="mt-6 max-w-[380px] text-[15px] leading-[1.7] opacity-80">
        Your documents, organised and under control. Secure document management
        for your whole organisation.
      </p>
    </div>
  )
}
