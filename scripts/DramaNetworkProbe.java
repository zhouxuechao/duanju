import java.net.URI;
import java.net.http.*;
import java.time.Duration;
class DramaNetworkProbe {
 public static void main(String[] args) {
  for(var version:HttpClient.Version.values()) {
   long start=System.nanoTime();
   try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).version(version).build()) {
    var response=client.send(HttpRequest.newBuilder(URI.create("https://ark.cn-beijing.volces.com/api/v3/responses")).timeout(Duration.ofSeconds(12)).GET().build(),HttpResponse.BodyHandlers.discarding());
    System.out.println(version+" HTTP="+response.statusCode()+" seconds="+(System.nanoTime()-start)/1e9);
   }catch(Exception e){System.out.println(version+" "+e.getClass().getSimpleName()+" seconds="+(System.nanoTime()-start)/1e9);}
  }
 }
}
