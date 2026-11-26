# The Meriva 3D model (Blender)

`meriva.glb` in `app/src/main/assets/web/` is built here, in Blender (5.3) from the command line, from the photos in
`ref/meriva_views.webp` (front, rear, both sides, 3/4 views, top). The photos are of the owner's car, so they aren't in
the repository; put your own montage there to rebuild the model.

```
python make_refs.py
"C:\Program Files\Blender Foundation\Blender 5.3\blender.exe" --background --factory-startup --python build_meriva.py -- <this folder> --stage full --decimate 0.45 --export <this folder>\out\meriva.glb
```

Then copy `out/meriva.glb` to `app/src/main/assets/web/meriva.glb` and bump `?v=` in `CAR_MODEL_URL` in the page.

- `build_meriva.py`: the body (a loft of cross-sections from the outlines in `PROFILE`, wheel wells cut with booleans),
  materials, export (core glTF only: Google's 3D map takes no extensions). Material names matter: the page recolours
  `Paint` and lights `TailRed`, `TailDark`, `BrakeLight`, `HeadChrome`, `HeadLens`; it turns `Wheel_FL/FR/RL/RR`.
- `details.py`: everything on the body (windows, lamps, grille, plates, badges, gaps) as outlines in a photo view laid
  onto the body by ray casting, plus wheels, mirrors, spoiler, aerial, exhaust.
- `views.py`: cameras solved to match the photos. `--render side_right,front34,...` renders them to `out/`;
  `overlay.py` and `sil.py` compare a render's outline with the photo, `montage.py` puts renders beside the photos.
- `--showcase out\show` renders studio views.

Axes: Blender +X = the car's left, -Y = forward, +Z = up; origin on the ground half way along the car (4.288 m long).
