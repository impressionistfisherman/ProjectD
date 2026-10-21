package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.Comparator;
import javax.tools.ToolProvider;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import kr.dalmaru.network.DalmaruPayloads;

final class StoryClientCheck {
    static void run()throws Exception {
        var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),RegistryAccess.EMPTY);
        try{var request=new DalmaruPayloads.StoryStateRequest(7,java.util.UUID.randomUUID());DalmaruPayloads.StoryStateRequest.CODEC.encode(buffer,request);if(!request.equals(DalmaruPayloads.StoryStateRequest.CODEC.decode(buffer)))throw new AssertionError("Story request codec");buffer.clear();var response=new DalmaruPayloads.StoryStateResponse(7,request.characterId(),"{\"title\":\"이야기\"}");DalmaruPayloads.StoryStateResponse.CODEC.encode(buffer,response);if(!response.equals(DalmaruPayloads.StoryStateResponse.CODEC.decode(buffer)))throw new AssertionError("Story response codec");}finally{buffer.release();}
        Path dir=Files.createTempDirectory("story-client-proof-");
        try {
            Path state=dir.resolve("ClientStoryState.java"),screen=dir.resolve("DalmaruStoryScreen.java"),harness=dir.resolve("StoryClientHarness.java");
            Files.copy(Path.of("src/client/java/kr/dalmaru/client/ClientStoryState.java"),state);
            String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruStoryScreen.java"));
            source=source.replaceAll("(?m)^import net\\.minecraft\\.[^;]+;\\R","");Files.writeString(screen,source);Files.writeString(harness,HARNESS);
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),state.toString(),screen.toString(),harness.toString())!=0)throw new AssertionError("Actual story client fixture compilation");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},StoryClientCheck.class.getClassLoader()){
                @Override protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException {synchronized(getClassLoadingLock(name)){Class<?> found=findLoadedClass(name);if(found==null&&name.startsWith("kr.dalmaru.client."))try{found=findClass(name);}catch(ClassNotFoundException ignored){}if(found==null)found=super.loadClass(name,false);if(resolve)resolveClass(found);return found;}}
            }){loader.loadClass("kr.dalmaru.client.StoryClientHarness").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(dir)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
    private static final String HARNESS="""
        package kr.dalmaru.client;
        import java.util.*;
        import java.util.function.Consumer;
        import kr.dalmaru.network.DalmaruPayloads;
        class Component{static String literal(String s){return s;}}
        class Font{int width(String s){return s.length()*7;}String plainSubstrByWidth(String s,int w){return s.substring(0,Math.min(s.length(),w/7));}}
        class Button{int x,y,width,height;boolean active;String text;int getWidth(){return width;}void setMessage(String s){text=s;}static Builder builder(String text,Consumer<Button> action){return new Builder(text);}static class Builder{Button button=new Button();Builder(String s){button.text=s;}Builder bounds(int x,int y,int w,int h){button.x=x;button.y=y;button.width=w;button.height=h;return this;}Button build(){return button;}}}
        class Minecraft{void setScreen(Screen s){}}
        class GuiGraphicsExtractor{int width,height,count;GuiGraphicsExtractor(int w,int h){width=w;height=h;}void text(Font font,String s,int x,int y,int color,boolean shadow){if(x<0||y<0||x+font.width(s)>width||y+9>height)throw new AssertionError("Story text clipped");count++;}}
        class Screen{int width=320,height=180;Font font=new Font();Minecraft minecraft=new Minecraft();List<Button>buttons=new ArrayList<>();Screen(String name){}protected void init(){}public void tick(){}public void extractRenderState(GuiGraphicsExtractor g,int x,int y,float d){}Button addRenderableWidget(Button b){if(b.x<0||b.y<0||b.x+b.width>width||b.y+b.height>height)throw new AssertionError("Story button clipped");for(var other:buttons)if(b.x<other.x+other.width&&b.x+b.width>other.x&&b.y<other.y+other.height&&b.y+b.height>other.y)throw new AssertionError("Story controls overlap");buttons.add(b);return b;}}
        class DalmaruClient{static void requestStoryState(){}static void storyOperation(String operation,String npc){}static String rejectionText(String result,String fallback){return fallback;}}
        public class StoryClientHarness{
            static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
            static String json(String title){return "{\\"finished\\":false,\\"chapterId\\":\\"story.dawn\\",\\"title\\":\\""+title+"\\",\\"combatXP\\":0,\\"combatGoal\\":0,\\"ready\\":false,\\"npcs\\":[\\"taepyeong.guide\\"],\\"missing\\":[\\"NPC 상호작용\\"]}";}
            public static void run(){
                UUID id=UUID.randomUUID();ClientStoryState.reset();ClientStoryState.selected(id);ClientStoryState.queried(1);
                ClientStoryState.accept(new DalmaruPayloads.StoryStateResponse(1,UUID.randomUUID(),json("foreign")));check(ClientStoryState.current()==null,"Foreign story accepted");
                ClientStoryState.accept(new DalmaruPayloads.StoryStateResponse(1,id,json("first")));check(ClientStoryState.current().get("title").getAsString().equals("first"),"Matching story rejected");
                ClientStoryState.queried(5);ClientStoryState.accept(new DalmaruPayloads.StoryStateResponse(5,id,json("latest")));ClientStoryState.pending(3);ClientStoryState.accept(new DalmaruPayloads.StoryStateResponse(3,id,json("old")));check(ClientStoryState.current().get("title").getAsString().equals("latest"),"Old operation replaced latest state");
                ClientStoryState.complete(4,false);check(ClientStoryState.waiting(),"Foreign operation cleared pending");ClientStoryState.complete(3,true);check(!ClientStoryState.waiting(),"Receipt did not release pending");
                ClientStoryState.queried(6);ClientStoryState.accept(new DalmaruPayloads.StoryStateResponse(6,id,"{}"));check(ClientStoryState.current().get("title").getAsString().equals("latest"),"Malformed state replaced valid state");
                for(int[] size:new int[][]{{320,180},{854,480}}){var screen=new DalmaruStoryScreen(null);screen.width=size[0];screen.height=size[1];screen.init();screen.tick();var graphics=new GuiGraphicsExtractor(size[0],size[1]);screen.extractRenderState(graphics,0,0,0);check(graphics.count==3&&screen.buttons.size()==5,"Story controls or status missing");}
                ClientStoryState.selected(UUID.randomUUID());check(ClientStoryState.current()==null&&!ClientStoryState.waiting(),"Character switch leaked story");
            }
        }
        """;
}
