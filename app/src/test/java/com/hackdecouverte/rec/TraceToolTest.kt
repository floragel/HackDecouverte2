package com.hackdecouverte.rec

import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.OccupancyGrid
import java.io.File
import java.util.Locale
import org.junit.Test

/**
 * Writes `tools/trace-<floor>.html`: the filter's own map (same walls, same east/north metres) where
 * the walker clicks the path they really took; the page gives the points to paste back (ground truth).
 */
class TraceToolTest {

    @Test
    fun writeTraceTool() {
        val site = IndoorSite.STANISLAS
        val floorId = site.defaultFloorId
        val map = WalkAnalysis.mapProvider(site)(floorId)!!
        val plan = FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset(floorId))!!)
        val g = map.grid
        fun f(v: Double) = "%.2f".format(Locale.ROOT, v)
        val rects = StringBuilder()
        for (row in 0 until g.height) {
            var col = 0
            while (col < g.width) {
                val v = g.cells[row * g.width + col]
                var end = col + 1
                while (end < g.width && g.cells[row * g.width + end] == v) end++
                val fill = when (v) { OccupancyGrid.WALL -> "#333"; OccupancyGrid.OUTSIDE -> "#e9e9e9"; else -> null }
                if (fill != null) {
                    val e = g.minEastM + col * g.resM
                    val n = g.minNorthM + (row + 1) * g.resM
                    rects.append("""<rect x="${f(e)}" y="${f(-n)}" width="${f((end - col) * g.resM + 0.02)}" height="${f(g.resM + 0.02)}" fill="$fill"/>""")
                }
                col = end
            }
        }
        val labels = plan.nodes.filter { it.label.isNotBlank() && it.type != "corridor" }.joinToString("") {
            """<text x="${f(it.posM.x + 0.3)}" y="${f(-it.posM.y - 0.3)}" class="lbl">${it.label}</text>"""
        }
        val minE = g.minEastM; val minN = g.minNorthM
        val w = g.width * g.resM; val h = g.height * g.resM
        val html = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tracer mon trajet</title>
<style>
 body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#fafafa;color:#222}
 header{padding:10px 16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;border-bottom:1px solid #ddd;background:#fff}
 button{font-size:15px;padding:8px 14px;border-radius:8px;border:1px solid #bbb;background:#fff;cursor:pointer}
 button.main{background:#1565c0;color:#fff;border-color:#1565c0}
 #map{display:block;width:100%;height:calc(100vh - 170px);cursor:crosshair;background:#fff;touch-action:none}
 .lbl{font-size:0.8px;fill:#1565c0}
 textarea{width:calc(100% - 32px);margin:8px 16px;height:60px;font-family:ui-monospace,monospace;font-size:12px}
 #hint{font-size:14px;color:#555}
</style></head><body>
<header>
 <span id="hint">Cliquez le trajet dans l'ordre (départ d'abord) : un clic à chaque changement de direction, porte ou demi-tour.</span>
 <button onclick="undo()">Annuler le dernier</button>
 <button onclick="clearAll()">Tout effacer</button>
 <button class="main" onclick="copyOut()">Copier pour Claude</button>
 <span id="count">0 point</span>
</header>
<svg id="map" viewBox="${f(minE - 1)} ${f(-(minN + h) - 1)} ${f(w + 2)} ${f(h + 2)}" preserveAspectRatio="xMidYMid meet">
 <g>$rects</g><g>$labels</g>
 <polyline id="path" fill="none" stroke="#e53935" stroke-width="0.25" stroke-linejoin="round" stroke-linecap="round"/>
 <g id="dots"></g>
</svg>
<textarea id="out" readonly placeholder="Les points apparaîtront ici (mètres est, nord)"></textarea>
<script>
const svg=document.getElementById('map'), pts=[];
function draw(){
 document.getElementById('path').setAttribute('points',pts.map(p=>p[0]+','+(-p[1])).join(' '));
 document.getElementById('dots').innerHTML=pts.map((p,i)=>'<circle cx="'+p[0]+'" cy="'+(-p[1])+'" r="'+(i==0?0.45:0.25)+'" fill="'+(i==0?'#43a047':'#e53935')+'"/>'+'<text x="'+(p[0]+0.35)+'" y="'+(-p[1]-0.35)+'" font-size="0.7" font-weight="bold">'+(i+1)+'</text>').join('');
 document.getElementById('count').textContent=pts.length+(pts.length>1?' points':' point');
 document.getElementById('out').value=pts.length?'TRAJET ${rec_floor(floorId)} '+JSON.stringify(pts):'';
}
svg.addEventListener('click',e=>{
 const pt=svg.createSVGPoint(); pt.x=e.clientX; pt.y=e.clientY;
 const p=pt.matrixTransform(svg.getScreenCTM().inverse());
 pts.push([Math.round(p.x*10)/10, Math.round(-p.y*10)/10]); draw();
});
function undo(){pts.pop();draw();}
function clearAll(){pts.length=0;draw();}
function copyOut(){const t=document.getElementById('out');t.select();navigator.clipboard?navigator.clipboard.writeText(t.value):document.execCommand('copy');document.getElementById('hint').textContent='Copié : colle-le dans le chat.';}
</script></body></html>
"""
        File("../tools").mkdirs()
        File("../tools/trace-$floorId.html").writeText(html)
    }

    private fun rec_floor(id: String) = id
}
