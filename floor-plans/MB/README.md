# MB building: floor plans and navigation graph

John Molson Building (MB), floors 1 to 7.

## Files
| File | What it is |
|---|---|
| `aligned/MB-floor-01.svg` … `07.svg` | Vector floor plans, traced from the evacuation plans. All floors share one frame. |
| `MB-graph.json` | Navigation graph of the whole building: 761 nodes, 300 rooms. |
| `export/floorplan-MB-1.json` … `7.json` | The same graph, one file per floor, in the Android app's `floorplan.json` format. |
| `MB-coordinates.json` | Coordinate system and scale of the plans. |
| `editor.html`, `editor_server.py`, `start-editor.bat` | Editor to view and edit the graph. |

## Coordinates
- **Units:** metres. x = east, y = true north.
- **Origin (0, 0):** the centre of the elevator lobby (between the 6 central elevators), the same point on every floor.
- **Scale:** lobby between the elevator banks = 3.46 m, fitted to the building's 70.7 m length on OpenStreetMap (about 1-2 % accuracy).
- **App export:** `x`, `y` are fractions of the 94 x 74 m plan, with y growing to the south.

## Graph
- **Node types:** `corridor`, `room`, `stairs`, `elevator`, `exit`.
- **Rooms are their doors:** a `room` node sits in the doorway, labelled with the room number (`MB-4.346`). A room with 2 doors has 2 nodes with the same label. To route to a room, aim for any node with its label.
- **Changing floor:** stairs and elevators connect through `vertical_group_id`:
  - `elevator_central` (floors 1-7)
  - `stairs_394`, `stairs_494` (floors 1-7)
  - `stairs_294` (floors 2-7)
  - `stairs_295` (grand stair, floors 1-3)
  - `stairs_194` (open stair, floors 4-7)
  - `stairs_1.294` (floor 1 only)
- **Accessibility:** links with `"accessible": false` are steps. Wheelchair routing skips them and all stairs.
- **Suggested costs:** walking = straight-line distance; stairs = 15 m per floor; elevator = 20 m + 3 m per floor.

## Editing
1. Double-click `start-editor.bat`, or run `python editor_server.py`. It opens http://127.0.0.1:8765/editor.html. Python 3 only, no packages to install.
2. Tools:
   - **N** add node (each new node links to the previous one; **Esc** ends the chain)
   - **L** link two nodes
   - **V** select and move
   - **T** test a route between two nodes, also across floors, with a wheelchair option
3. **Check** lists unlinked nodes and links that may cross walls. **Save** writes `MB-graph.json`. **Export for app** writes the `export/` files.

## Things still to verify
- **Guessed doors:** search the names for "(door guessed)" (about 30 rooms).
- **Floor 7 (blurry photo):** some room numbers are unsure (marked in the node names).
- **Stairwell 1.294:** a separate shaft from x.294 above. The x.294 stairwell may land on floor 1 near 1.350; not linked yet.
