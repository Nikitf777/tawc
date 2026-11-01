This is a fork of TAWC - an Android application for running Linux GUI applications with GPU-acceleration in chroot-like containers.
Originally, the project had the following parts:
- a Smithay-based Wayland compositor that creates separate Android activities for each Wayland window, which adds more native feel for these applications;
- `tawcroot` - a proot-like solution with better performance, but worse isolation (per author's claims);
- a distro-management subsystem;
- a task manager.

This fork has two goal:
- split the original monolithic architecture into reusable parts;
- find issues in the projects we depend on and potentially contribute upstream to fix them (which doesn't necessarily mean that all the patches will be submitted to upstream).
The fork doesn't aim to upstream the changes, as the author clearly state that they don't want to split it into smaller reusable components ([the statement](https://github.com/wmww/tawc/pull/33#issuecomment-6278906131))

Use one of the two following ways to import dependency:
- git submodules;
- native packages for Cargo or Gradle build systems;

A dependency can be in one of the following three states of readiness for our project:
1. ready out of the box;
2. needs small patches;
3. needs major patches/refactoring.

If it needs small patches, we should implement them and try to make them upstream-friendly.
"Upstream-friendly" doesn't necessarily mean that the upstream maintainers will 100% accept it. But it means that the patch isn't specific to this project, doesn't go against the fundamental goals of the upstream project and might be genuinely useful for upstream because it covers a valid use-case. And generally robust.
Non-upstream-friendly patches can be made when we're just investigating if something is even possible (proof-of-concept stage).
A very common issue in this domain is when the authors/maintainers don't realize (or don't care) that Android is a valid Linux-based OS that can often run Linux software with little to no modifications.
If a dependency can satisfy out needs with upstream-friendly patches, it must be imported as a git submodule.
If it can't (that's the third state of readiness), then ask what to do, because in this case a decision needs to be made individually per dependency.

The original AGENTS.md is `AGENTS_ORIGINAL.md`.