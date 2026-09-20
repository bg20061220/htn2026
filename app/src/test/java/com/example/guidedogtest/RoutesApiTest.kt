package com.example.guidedogtest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Routes API response is now the one route source in the app - the map draws it, the follower
 * drives it, and the voice reads its instructions - so the parsing is worth pinning: a step that
 * loses its end location would silently stop the robot steering, and a mis-decoded polyline would
 * draw a route the robot does not walk.
 */
class RoutesApiTest {

    /** Two steps, a duration and a polyline, as computeRoutes returns them. */
    private val response = """
        {
          "routes": [
            {
              "distanceMeters": 151,
              "duration": "120s",
              "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
              "legs": [
                {
                  "steps": [
                    {
                      "distanceMeters": 51,
                      "navigationInstruction": { "instructions": "Head northeast" },
                      "endLocation": { "latLng": { "latitude": 43.4727, "longitude": -80.5444 } }
                    },
                    {
                      "distanceMeters": 100,
                      "navigationInstruction": { "instructions": "Turn right onto Ring Road" },
                      "endLocation": { "latLng": { "latitude": 43.4731, "longitude": -80.5439 } }
                    }
                  ]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesTheStepsTheRobotDrives() {
        val plan = RoutesApi.parse(response)

        assertEquals(2, plan.steps.size)
        assertEquals("Head northeast", plan.steps[0].instruction)
        assertEquals(51, plan.steps[0].distanceMeters)
        assertEquals(43.4727, plan.steps[0].endLat, 1e-6)
        assertEquals(-80.5444, plan.steps[0].endLng, 1e-6)
        assertEquals("Turn right onto Ring Road", plan.steps[1].instruction)
        assertEquals(43.4731, plan.steps[1].endLat, 1e-6)

        assertEquals(100.0, Geo.distanceMeters(
            GeoPoint(plan.steps[0].endLat, plan.steps[0].endLng),
            GeoPoint(plan.steps[1].endLat, plan.steps[1].endLng),
        ), 40.0)
    }

    @Test
    fun parsesTheSummaryTheMapAndVoiceShow() {
        val plan = RoutesApi.parse(response)

        assertEquals(151, plan.distanceMeters)
        assertEquals(120.0, plan.durationSeconds, 1e-6)
        assertEquals(
            listOf("Head northeast (51 m)", "Turn right onto Ring Road (100 m)"),
            plan.instructions,
        )
    }

    @Test
    fun decodesThePolylineForTheMap() {
        // Google's own worked example: three points down the west coast.
        val points = RoutesApi.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")

        assertEquals(3, points.size)
        assertEquals(38.5, points[0].latitude, 1e-6)
        assertEquals(-120.2, points[0].longitude, 1e-6)
        assertEquals(40.7, points[1].latitude, 1e-6)
        assertEquals(-120.95, points[1].longitude, 1e-6)
        assertEquals(43.252, points[2].latitude, 1e-6)
        assertEquals(-126.453, points[2].longitude, 1e-6)
    }

    @Test
    fun aRouteWithoutADrawableLineStillDrives() {
        val withoutPolyline = """{"routes":[{"distanceMeters":10,"duration":"60s","legs":[{"steps":[
            {"distanceMeters":10,"navigationInstruction":{"instructions":"continue"},
             "endLocation":{"latLng":{"latitude":1.0,"longitude":2.0}}}]}]}]}"""

        val plan = RoutesApi.parse(withoutPolyline)

        assertEquals(1, plan.steps.size)
        assertTrue(plan.points.isEmpty())
    }

    @Test
    fun anEmptyResponseIsAnErrorNotAnEmptyRoute() {
        val failing = runCatching { RoutesApi.parse("""{"routes":[]}""") }

        assertTrue(failing.isFailure)
    }
}
