package com.hackdecouverte.rec

import com.hackdecouverte.map.FloorPlan
import com.hackdecouverte.map.IndoorSite
import com.hackdecouverte.map.MapTestSupport
import com.hackdecouverte.map.OccupancyGrid
import com.hackdecouverte.pdr.PointM
import com.hackdecouverte.rec.truth.Checkpoint
import com.hackdecouverte.rec.truth.RouteLabel
import com.hackdecouverte.rec.truth.TruthTrack
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Writes `tools/trace-<floor>.html` (v2) for every floor: the filter's own map (same walls, same
 * east/north metres) where the walker clicks the route they really took, optionally with the time
 * each point was reached and a label (porte, demi-tour, salle, escalier, pivot). The page gives the
 * route as `TRUTH {json v2}` to paste, or downloads `<walk>.truth.json` for recordings/.
 */
class TraceToolTest {

    private val floors = listOf(IndoorSite.STANISLAS to "STAN-1") + (1..7).map { IndoorSite.MB to "MB-$it" }

    @Test
    fun writesOneToolPerFloor() {
        for ((site, floorId) in floors) writeTool(site, floorId)
        for ((site, floorId) in floors) {
            val f = File("../tools/trace-$floorId.html")
            assertTrue("${f.path} missing", f.isFile)
            val text = f.readText()
            assertTrue(text.contains("\"$floorId\""))
            val g = WalkAnalysis.mapProvider(site)(floorId)!!.grid
            assertTrue(text.contains("viewBox=\"${f(g.minEastM - 1)} ${f(-(g.minNorthM + g.height * g.resM) - 1)}"))
        }
    }

    @Test
    fun exampleOutputParses() {
        val text = exampleJson("W1", "STAN-1", listOf(PointM(1.0, 2.0), PointM(3.5, -4.25)), listOf(Checkpoint(1, 12.5)), listOf(RouteLabel(1, "porte")))
        val r = TruthTrack.parse("TRUTH $text")
        assertEquals(listOf(PointM(1.0, 2.0), PointM(3.5, -4.25)), r.path)
        assertEquals(Checkpoint(1, 12.5), r.checkpoints.single())
        assertEquals("porte", r.labels.single().label)
    }

    @Test
    fun pastedV2Parses() {
        // As copied from the page (browser JSON.stringify).
        val r = TruthTrack.parse("""TRUTH {"version":2,"walk":"x","floor":"MB-2","path":[[0.5,1],[2,3.5],[4,4]],"checkpoints":[{"i":2,"tS":61}],"labels":[{"i":0,"label":"salle"}]}""")
        assertEquals(3, r.path.size)
        assertEquals("MB-2", r.floorId)
        assertEquals(61.0, r.checkpoints.single().tS, 0.0)
    }

    private fun f(v: Double) = "%.2f".format(Locale.ROOT, v)

    /** Same JSON shape as the page's script (kept in step with it). */
    private fun exampleJson(walk: String, floorId: String, pts: List<PointM>, cps: List<Checkpoint>, labels: List<RouteLabel>): String =
        """{"version":2,"walk":"$walk","floor":"$floorId","path":[${pts.joinToString(",") { "[${it.x},${it.y}]" }}],""" +
            """"checkpoints":[${cps.joinToString(",") { "{\"i\":${it.pointIndex},\"tS\":${it.tS}}" }}],""" +
            """"labels":[${labels.joinToString(",") { "{\"i\":${it.pointIndex},\"label\":\"${it.label}\"}" }}]}"""

    private fun writeTool(site: IndoorSite, floorId: String) {
        val map = WalkAnalysis.mapProvider(site)(floorId)!!
        val plan = FloorPlan.parse(MapTestSupport.open(site.floorPlanAsset(floorId))!!)
        val g = map.grid
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
        val w = g.width * g.resM
        val h = g.height * g.resM
        val html = """<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Tracer mon trajet · $floorId</title>
<style>
 body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#fafafa;color:#222}
 header{padding:10px 16px;display:flex;gap:8px;flex-wrap:wrap;align-items:center;border-bottom:1px solid #ddd;background:#fff}
 button{font-size:15px;padding:8px 14px;border-radius:8px;border:1px solid #bbb;background:#fff;cursor:pointer}
 button.main{background:#1565c0;color:#fff;border-color:#1565c0}
 input{font-size:14px;padding:6px 8px;border:1px solid #bbb;border-radius:6px}
 main{display:flex;flex-wrap:wrap}
 #map{flex:1 1 520px;height:calc(100vh - 120px);min-height:360px;cursor:crosshair;background:#fff;touch-action:none}
 aside{flex:0 1 300px;max-height:calc(100vh - 120px);overflow:auto;border-left:1px solid #ddd;background:#fff;padding:8px 12px}
 aside .row{display:flex;gap:6px;align-items:center;margin:4px 0;font-size:13px}
 aside .row input{width:64px}
 .lbl{font-size:0.8px;fill:#1565c0}
 #hint{font-size:14px;color:#555}
 @media (max-width:700px){aside{flex:1 1 100%;border-left:none;border-top:1px solid #ddd;max-height:none}#map{height:60vh}}
</style></head><body>
<header>
 <input id="walk" placeholder="nom de la marche (ex. STAN-1_20261118-150346)" size="34">
 <button onclick="undo()">Annuler le dernier</button>
 <button onclick="clearAll()">Tout effacer</button>
 <button class="main" onclick="copyOut()">Copier pour Claude</button>
 <button onclick="download()">Télécharger</button>
 <span id="hint">Cliquez le trajet dans l'ordre, départ d'abord. Instant (s) et libellé : facultatifs, à droite.</span>
</header>
<main>
<svg id="map" viewBox="${f(g.minEastM - 1)} ${f(-(g.minNorthM + h) - 1)} ${f(w + 2)} ${f(h + 2)}" preserveAspectRatio="xMidYMid meet">
 <g>$rects</g><g>$labels</g>
 <polyline id="path" fill="none" stroke="#e53935" stroke-width="0.25" stroke-linejoin="round" stroke-linecap="round"/>
 <g id="dots"></g>
</svg>
<aside id="list"></aside>
</main>
<script>
const FLOOR="$floorId", LABELS=["","porte","demi-tour","salle","escalier","pivot"];
const svg=document.getElementById('map'), pts=[];
function out(){
 const cps=[],lbs=[];
 pts.forEach((p,i)=>{ if(p.t!==''&&!isNaN(parseFloat(p.t))) cps.push({i:i,tS:parseFloat(p.t)}); if(p.label) lbs.push({i:i,label:p.label}); });
 return JSON.stringify({version:2,walk:document.getElementById('walk').value.trim(),floor:FLOOR,path:pts.map(p=>[p.e,p.n]),checkpoints:cps,labels:lbs});
}
function draw(){
 document.getElementById('path').setAttribute('points',pts.map(p=>p.e+','+(-p.n)).join(' '));
 document.getElementById('dots').innerHTML=pts.map((p,i)=>'<circle cx="'+p.e+'" cy="'+(-p.n)+'" r="'+(i==0?0.45:0.25)+'" fill="'+(i==0?'#43a047':'#e53935')+'"/><text x="'+(p.e+0.35)+'" y="'+(-p.n-0.35)+'" font-size="0.7" font-weight="bold">'+(i+1)+'</text>').join('');
 document.getElementById('list').innerHTML=pts.length?pts.map((p,i)=>'<div class="row"><b>'+(i+1)+'</b><input placeholder="s" value="'+p.t+'" oninput="pts['+i+'].t=this.value"><select onchange="pts['+i+'].label=this.value">'+LABELS.map(l=>'<option'+(l==p.label?' selected':'')+' value="'+l+'">'+(l||'—')+'</option>').join('')+'</select></div>').join(''):'<p>Aucun point.</p>';
}
svg.addEventListener('click',e=>{
 const pt=svg.createSVGPoint(); pt.x=e.clientX; pt.y=e.clientY;
 const p=pt.matrixTransform(svg.getScreenCTM().inverse());
 pts.push({e:Math.round(p.x*10)/10,n:Math.round(-p.y*10)/10,t:'',label:''}); draw();
});
function undo(){pts.pop();draw();}
function clearAll(){pts.length=0;draw();}
function copyOut(){const t='TRUTH '+out(); if(navigator.clipboard) navigator.clipboard.writeText(t); document.getElementById('hint').textContent='Copié : colle-le dans le chat.';}
function download(){const name=(document.getElementById('walk').value.trim()||FLOOR+'-trajet')+'.truth.json';const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([out()],{type:'application/json'}));a.download=name;a.click();}
draw();
</script></body></html>
"""
        File("../tools").mkdirs()
        File("../tools/trace-$floorId.html").writeText(html)
    }
}
