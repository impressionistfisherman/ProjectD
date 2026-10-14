package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class ClientScreenBoundsCheck {
    public static void main(String[] args)throws Exception {
        Path base=Path.of("src/client/java/kr/dalmaru/client");String skill=Files.readString(base.resolve("DalmaruSkillScreen.java")),run=Files.readString(base.resolve("DalmaruRunScreen.java"));
        String equipment=Files.readString(base.resolve("DalmaruEquipmentScreen.java")),profile=Files.readString(base.resolve("DalmaruProfileScreen.java"));
        Path dir=Files.createTempDirectory("client-screen-bounds-");Path file=dir.resolve("ScreenHarness.java");
        Files.writeString(file,HARNESS.replace("/* SKILL */",method(skill,"protected void init()" )).replace("/* RUN */",method(run,"protected void init()")).replace("/* EQUIPMENT */",method(equipment,"protected void init()")+method(equipment,"private void rebuild()")+method(equipment,"private static Component message(")).replace("/* PROFILE */",method(profile,"protected void init()")+method(profile,"private Button statButton(")));
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Screen fixture compile failed");
        try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("ScreenHarness").getMethod("run").invoke(null);}
        System.out.println("Actual skill/run/profile/equipment bounds and paging PASS (320x180 to960x540)");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class Component{String text;Component(String text){this.text=text;}static Component literal(String s){return new Component(s);}}
        class Button{int x,y,w,h;boolean active=true;java.util.function.Consumer<Button> action;void setMessage(Component message){}static Button builder(Component s,java.util.function.Consumer<Button> action){Button button=new Button();button.action=action;return button;}Button bounds(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}Button build(){return this;}}
        class Screen{int width,height;Screen minecraft=this;List<Button> buttons=new ArrayList<>();protected void init(){}void setScreen(Screen s){}void onClose(){}void clearWidgets(){buttons.clear();}Button addRenderableWidget(Button b){buttons.add(b);return b;}void tick(){}}
        class Profile{String profession(){return "WARRIOR";}}
        class Response{Profile profile(){return new Profile();}}
        class ClientProfileState{static Response current(){return new Response();}}
        enum CharacterProfession{WARRIOR}
        class SkillDefinition{String id(){return "skill";}String koreanName(){return "skill";}static List<SkillDefinition>forProfession(CharacterProfession p){return java.util.stream.IntStream.range(0,8).mapToObj(i->new SkillDefinition()).toList();}}
        class DalmaruClient{static void requestSkill(String s){}static void requestSkillRanks(){}static void requestLoadout(){}static void requestRun(String s){}static void readyRun(){}static void requestEquipment(){}static List<UUID>moved=new ArrayList<>(),enhanced=new ArrayList<>();static void requestEquipmentMove(UUID id){moved.add(id);}static void requestEnhancement(UUID id){enhanced.add(id);}static void requestCharacterStatReset(){}static void requestCharacterStatInvestment(int id){}}
        class ClientRunState{static String status(){return "READY";}}
        class SkillScreen extends Screen{SkillScreen(){}SkillScreen(Screen p){}Screen previous;List<Button>slotButtons=new ArrayList<>(),learnButtons=new ArrayList<>();List<SkillDefinition>skills=List.of();void cycle(int i){}/* SKILL */}
        class RunScreen extends Screen{Button status,firstStep,training,ready;/* RUN */}
        class DalmaruProfessionScreen extends Screen{DalmaruProfessionScreen(Screen previous){}}
        class DalmaruSkillScreen extends Screen{DalmaruSkillScreen(Screen previous){}}
        class DalmaruPayloads{record EquipmentData(UUID equipmentId,int slotId,String itemId,int enhancement,int consecutiveFailures){}}
        class ClientEquipmentState{static List<DalmaruPayloads.EquipmentData>items=List.of();static List<DalmaruPayloads.EquipmentData>equipment(){return items;}}
        class EquipmentScreen extends Screen{List<Button>buttons=new ArrayList<>();List<UUID>displayedIds=List.of();int page,displayedPage=-1;/* EQUIPMENT */}
        class ProfileScreen extends Screen{Button statusButton,strengthButton,dexterityButton,arcanaButton,vitalityButton,spiritButton,resetButton,professionButton,skillButton;/* PROFILE */}
        public class ScreenHarness{
        static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
        public static void run(){for(int[]size:new int[][]{{320,180},{320,240},{427,240},{640,360},{960,540}})for(Screen screen:List.of(new SkillScreen(),new RunScreen(),new ProfileScreen(),new EquipmentScreen())){
        screen.width=size[0];screen.height=size[1];screen.init();require(screen.buttons.size()==(screen instanceof SkillScreen?14:screen instanceof RunScreen?5:screen instanceof ProfileScreen?9:3),"Missing actions");
        for(var b:screen.buttons)require(b.x>=0&&b.y>=0&&b.w>0&&b.h>0&&b.x+b.w<=screen.width&&b.y+b.h<=screen.height,"Clipped action at "+size[0]+"x"+size[1]);
        for(int i=0;i<screen.buttons.size();i++)for(int j=i+1;j<screen.buttons.size();j++){var a=screen.buttons.get(i);var b=screen.buttons.get(j);require(a.x+a.w<=b.x||b.x+b.w<=a.x||a.y+a.h<=b.y||b.y+b.h<=a.y,"Overlapping actions at "+size[0]+"x"+size[1]);}
        }
        var equipment=new EquipmentScreen();equipment.width=320;equipment.height=180;
        for(int count:new int[]{0,1,5,6,30}){
            ClientEquipmentState.items=java.util.stream.IntStream.range(0,count).mapToObj(i->new DalmaruPayloads.EquipmentData(UUID.randomUUID(),-1,"item",0,0)).toList();
            DalmaruClient.moved.clear();DalmaruClient.enhanced.clear();equipment.page=0;equipment.clearWidgets();equipment.init();
            while(true){
                for(var button:((Screen)equipment).buttons)require(button.x>=0&&button.y>=0&&button.x+button.w<=320&&button.y+button.h<=180,"Equipment paging bounds");
                for(int i=0;i<equipment.buttons.size();i++){equipment.buttons.get(i).action.accept(equipment.buttons.get(i));var enhance=((Screen)equipment).buttons.get(i*2+1);enhance.action.accept(enhance);}
                var next=((Screen)equipment).buttons.get(((Screen)equipment).buttons.size()-2);if(!next.active)break;next.action.accept(next);
            }
            var ids=ClientEquipmentState.items.stream().map(DalmaruPayloads.EquipmentData::equipmentId).toList();require(DalmaruClient.moved.equals(ids)&&DalmaruClient.enhanced.equals(ids),"Equipment page skips/duplicates/actions");
            equipment.clearWidgets();equipment.init();require(!((Screen)equipment).buttons.isEmpty(),"Resize lost controls");
            ClientEquipmentState.items=List.of();equipment.init();require(equipment.page==0&&((Screen)equipment).buttons.size()==3,"Empty/shrinking equipment controls");
        }
        }
        }
        """;
}
