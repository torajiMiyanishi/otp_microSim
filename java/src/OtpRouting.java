import org.opentripplanner.graph_builder.model.GtfsBundle;
import org.opentripplanner.graph_builder.module.GtfsFeedId;
import org.opentripplanner.graph_builder.module.GtfsModule;
import org.opentripplanner.graph_builder.linking.TransitToStreetNetworkModule;
import org.opentripplanner.graph_builder.module.osm.OpenStreetMapModule;
import org.opentripplanner.routing.error.TrivialPathException;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.api.model.Itinerary;
import org.opentripplanner.api.model.Leg;
import org.opentripplanner.api.model.TripPlan;
import org.opentripplanner.api.model.WalkStep;
import org.opentripplanner.api.resource.GraphPathToTripPlanConverter;
import org.opentripplanner.common.model.GenericLocation;
import org.opentripplanner.openstreetmap.impl.BinaryFileBasedOpenStreetMapProviderImpl;
import org.opentripplanner.routing.core.RoutingRequest;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.impl.DefaultStreetVertexIndexFactory;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.standalone.Router;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;



public class OtpRouting {

    public Graph graph;

    public void initializeOtp(String pathToPbf, String dirToGtfs) throws IOException {
        graph = new Graph();


        // set osm module
        HashMap<Class<?>, Object> extra = new HashMap<>();
        BinaryFileBasedOpenStreetMapProviderImpl osmProvider = new BinaryFileBasedOpenStreetMapProviderImpl();
        osmProvider.setPath(new File(pathToPbf));
        SimpleOpenStreetMapContentHandler handler = new SimpleOpenStreetMapContentHandler();
        osmProvider.readOSM(handler);
        OpenStreetMapModule osmModule = new OpenStreetMapModule(Collections.singletonList(osmProvider));
        osmModule.buildGraph(graph, extra);

        // set gtfs module
        List<GtfsBundle> gtfsBundles = null;
        try (final Stream<Path> pathStream = Files.list(Paths.get(dirToGtfs))) {
            gtfsBundles = pathStream.map(Path::toFile).filter(file -> file.getName().toLowerCase().endsWith(".zip"))
                    .map(file -> {
                        GtfsBundle gtfsBundle = new GtfsBundle(file);
                        gtfsBundle.setTransfersTxtDefinesStationPaths(true);
                        String id = file.getName().substring(0, file.getName().length() - 4);
                        gtfsBundle.setFeedId(new GtfsFeedId.Builder().id(id).build());
                        return gtfsBundle;
                    }).collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        GtfsModule gtfsModule = new GtfsModule(gtfsBundles);
        gtfsModule.buildGraph(graph, null);

        TransitToStreetNetworkModule linkModule = new TransitToStreetNetworkModule();
        linkModule.buildGraph(graph, null);

        graph.index(new DefaultStreetVertexIndexFactory());


    }


    public Result routingRequest(int hour, int minute, double o_lat, double o_lon, double d_lat, double d_lon){
//        LocalDateTime ldt = LocalDateTime.parse("2024-08-01T13:00");
        LocalDateTime ldt = LocalDateTime.of(2024,4,24,hour,minute);
        RoutingRequest routingRequest = new RoutingRequest();
        routingRequest.dateTime = Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant()).getTime() / 1000;
        routingRequest.from = new GenericLocation(o_lat, o_lon);
        routingRequest.to = new GenericLocation(d_lat, d_lon);
        routingRequest.setNumItineraries(3);
        routingRequest.setArriveBy(false);
        routingRequest.ignoreRealtimeUpdates = true;
        routingRequest.reverseOptimizing = true;
        routingRequest.onlyTransitTrips = false;
        try {
            routingRequest.setRoutingContext(graph);
        } catch(TrivialPathException tpe) {
            Result result = new Result(false,null,null);
            return result;
        }

        routingRequest.setModes(new TraverseModeSet(TraverseMode.TRANSIT,TraverseMode.WALK));
        System.out.println("routing request@"+hour+"@"+minute+"@"+o_lat+"@"+o_lon+"@"+d_lat+"@"+d_lon);
        Router router = new Router("OTP_GTFS", graph);
        List<GraphPath> paths = new GraphPathFinder(router).getPaths(routingRequest);
        if (paths.isEmpty()) { // transit route無し
            System.out.println("transit route無し");
            routingRequest.setModes(new TraverseModeSet(TraverseMode.WALK));
            paths = new GraphPathFinder(router).getPaths(routingRequest);
        } else if (GraphPathToTripPlanConverter.generatePlan(paths, routingRequest).itinerary.get(0).startTime.get(Calendar.DATE) > 24) { // transit routeが日付を超える場合
            System.out.println("transit routeが日付を超える場合");
            routingRequest.setModes(new TraverseModeSet(TraverseMode.WALK));
            paths = new GraphPathFinder(router).getPaths(routingRequest);
        }
        TripPlan tripPlan;
        try{
             tripPlan = GraphPathToTripPlanConverter.generatePlan(paths, routingRequest);
        }catch(IndexOutOfBoundsException idx){
            Result result = new Result(false,null,null);
            return result;
        }

        Itinerary plan = tripPlan.itinerary.get(0);
        int durationOfTripping = plan.duration.intValue()/60;

        LocalTime endTripTime = LocalTime.of(plan.endTime.get(Calendar.HOUR_OF_DAY),plan.endTime.get(Calendar.MINUTE));

        List<String> records = new ArrayList<>();
        String startTime;
        String endTime;
        String mode;
        String latlonHash;
        String latlonStay;
        String record;
        if (plan.legs.size() == 1){ // case:walk only
            System.out.println("WALK_ONLY");
            Leg l = plan.legs.get(0);
            startTime = LocalTime.of(l.startTime.get(Calendar.HOUR_OF_DAY),l.startTime.get(Calendar.MINUTE)).toString();
            latlonHash = l.legGeometry.getPoints();
            mode = l.mode;
            endTime = LocalTime.of(l.endTime.get(Calendar.HOUR_OF_DAY),l.endTime.get(Calendar.MINUTE)).toString();
            record = mode+","+latlonHash+","+startTime+","+endTime+",";
            records.add(record);
        } else { // case:transit trip
            for(Leg l: plan.legs) {
                startTime = LocalTime.of(l.startTime.get(Calendar.HOUR_OF_DAY),l.startTime.get(Calendar.MINUTE)).toString();
                latlonHash = l.legGeometry.getPoints();
                mode = l.mode;
                endTime = LocalTime.of(l.endTime.get(Calendar.HOUR_OF_DAY),l.endTime.get(Calendar.MINUTE)).toString();
                record = mode+","+latlonHash+","+startTime+","+endTime+",";
                records.add(record);
            }
        }
        Result result = new Result(true,records,endTripTime);
        return result;
    }

    public class Result {
        private  boolean isSuccessed;
        private List<String> stringList;
        private LocalTime localTime;

        public Result(boolean isSuccessed ,List<String> stringList, LocalTime localTime) {
            this.isSuccessed = isSuccessed;
            this.stringList = stringList;
            this.localTime = localTime;
        }

        public boolean getIsSuccessed() { return isSuccessed; }
        public List<String> getStringList() {
            return stringList;
        }

        public LocalTime getLocalTime() {
            return localTime;
        }
    }


    // 二地点間の直線距離計算 ※近すぎる二点のルーティングがエラーの基となるため
    public double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.01; // 地球の半径（キロメートル）
        // 緯度経度をラジアンに変換
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        lat1 = Math.toRadians(lat1);
        lat2 = Math.toRadians(lat2);

        // ハーバーサイン公式による距離の計算
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.pow(Math.sin(dLon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = R * c; // 結果をキロメートル単位で出力

        return distance;
    }
}


