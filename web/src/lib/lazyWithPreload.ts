import { lazy, type ComponentType } from "react";

/**
 * `React.lazy` for a named export, plus a `preload()` that starts fetching the chunk before anything
 * renders it — so a menu can begin loading a section when the pointer or focus reaches its item, and
 * the click usually finds the code already there (#1092). Loading happens once however it starts:
 * `preload()` and the first render share one promise.
 */
export function lazyWithPreload<P extends object>(
  load: () => Promise<ComponentType<P>>,
) {
  let loading: Promise<{ default: ComponentType<P> }> | undefined;
  const loadOnce = () => {
    loading ??= load().then((component) => ({ default: component }));
    return loading;
  };
  return Object.assign(lazy(loadOnce), { preload: loadOnce });
}
