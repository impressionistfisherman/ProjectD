package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class ClientScreenBoundsCheck {
    public static void main(String[] args)throws Exception {
        Path base=Path.of("src/client/java/kr/dalmaru/client");String skill=Files.readString(base.resolve("DalmaruSkillScreen.java")),run=Files.readString(base.resolve("DalmaruRunScreen.java"));
        String quest=Files.readString(base.resolve("DalmaruQuestScreen.java"));String equipment=Files.readString(base.resolve("DalmaruEquipmentScreen.java")),profile=Files.readString(base.resolve("DalmaruProfileScreen.java"));
        Path dir=Files.createTempDirectory("client-screen-bounds-");Path file=dir.resolve("ScreenHarness.java");
        String village=Files.readString(base.resolve("DalmaruVillageScreen.java"));
        String craft=Files.readString(base.resolve("DalmaruCraftScreen.java"));
        String precision=Files.readString(base.resolve("DalmaruPrecisionScreen.java"));
        String orders=Files.readString(base.resolve("DalmaruCraftOrderScreen.java"));
        String life=Files.readString(base.resolve("DalmaruLifeScreen.java"));
        String lifeSpec=Files.readString(base.resolve("DalmaruLifeSpecScreen.java"));
        String housing=Files.readString(base.resolve("DalmaruHousingScreen.java"));
        String investigation=Files.readString(base.resolve("DalmaruInvestigationScreen.java"));
        String party=Files.readString(base.resolve("DalmaruPartyScreen.java"));
        Files.writeString(file,HARNESS.replace("/* SKILL */",method(skill,"protected void init()" )).replace("/* RUN */",method(run,"protected void init()")).replace("/* QUEST */",method(quest,"protected void init()")).replace("/* EQUIPMENT */",method(equipment,"protected void init()")+method(equipment,"private void rebuild()")+method(equipment,"private static Component message(")).replace("/* PROFILE */",method(profile,"protected void init()")+method(profile,"private Button statButton(")).replace("/* VILLAGE */",method(village,"protected void init()")).replace("/* CRAFT */",method(craft,"protected void init()")).replace("/* PRECISION */",method(precision,"protected void init()")).replace("/* ORDER */",method(orders,"protected void init()")).replace("/* LIFE */",method(life,"protected void init()")).replace("/* LIFE_SPEC */",method(lifeSpec,"protected void init()")).replace("/* HOUSING */",method(housing,"protected void init()")).replace("/* INVESTIGATION */",method(investigation,"protected void init()")).replace("/* PARTY */",method(party,"protected void init()")));
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Screen fixture compile failed");
        try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClientScreenBoundsCheck.class.getClassLoader())){loader.loadClass("ScreenHarness").getMethod("run").invoke(null);}
        System.out.println("Actual skill/run/quest/profile/equipment bounds and paging PASS (320x180 to960x540)");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class Component{String text;Component(String text){this.text=text;}static Component empty(){return new Component("");}static Component literal(String s){return new Component(s);}}
        class Button{int x,y,w,h;boolean active=true;boolean visible=true;java.util.function.Consumer<Button> action;void setMessage(Component message){}static Button builder(Component s,java.util.function.Consumer<Button> action){Button button=new Button();button.action=action;return button;}Button bounds(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}Button build(){return this;}}
        class EditBox{int x,y,w,h;String value="";EditBox(Object font,int x,int y,int w,int h,Component label){this.x=x;this.y=y;this.w=w;this.h=h;}void setMaxLength(int length){}String getValue(){return value;}}
        class Screen{int width,height;Object font=new Object();Screen minecraft=this;List<Button> buttons=new ArrayList<>();List<EditBox> boxes=new ArrayList<>();protected void init(){}void setScreen(Screen s){}void onClose(){}void clearWidgets(){buttons.clear();boxes.clear();}Button addRenderableWidget(Button b){buttons.add(b);return b;}EditBox addRenderableWidget(EditBox box){boxes.add(box);return box;}void tick(){}}
        class Profile{String profession(){return "WARRIOR";}}
        class Response{Profile profile(){return new Profile();}}
        class ClientProfileState{static Response current(){return new Response();}}
        enum CharacterProfession{WARRIOR}
        class SkillDefinition{String id(){return "skill";}String koreanName(){return "skill";}static List<SkillDefinition>forProfession(CharacterProfession p){return java.util.stream.IntStream.range(0,8).mapToObj(i->new SkillDefinition()).toList();}}
        class DalmaruClient{static void requestQuest(boolean accept){}static void requestSkill(String s){}static void requestSkillRanks(){}static void requestLoadout(){}static void requestRun(String s){}static void readyRun(){}static void openParty(){}static List<String> partyActions=new ArrayList<>();static void requestParty(String action){partyActions.add(action);}static void requestParty(String action,String body){partyActions.add(action);}static void requestEquipment(){}static List<UUID>moved=new ArrayList<>(),enhanced=new ArrayList<>();static void requestEquipmentMove(UUID id){moved.add(id);}static void requestEnhancement(UUID id){enhanced.add(id);}static void requestCharacterStatReset(){}static void requestCharacterStatInvestment(int id){}static void requestVillages(){}static void requestVillage(String id){}static void openHome(){}static void requestHome(String operation){}static void requestQuestStage(String stage,String action){}static void requestLifeProfile(){}static void requestLifeLearn(String job){}static void requestLifeSpecialize(String job,String specialization){}static void requestLifeTask(String job){}static void requestCraftResearch(){}static void requestLearnedCraft(){}static void requestPrecisionBegin(){}static void requestPrecisionStep(int step,String choice){}static void requestCraftOrderPlace(){}static void requestCraftOrderAccept(){}static void requestCraftOrderSettle(){}static void requestCraftOrderCancel(){}}
        class ClientQuestStageState{static String stage(){return "field";}static void stage(String value){}}
        class ClientRunState{static String status(){return "READY";}}
        class SkillScreen extends Screen{SkillScreen(){}SkillScreen(Screen p){}Screen previous;List<Button>slotButtons=new ArrayList<>(),learnButtons=new ArrayList<>();List<SkillDefinition>skills=List.of();void cycle(int i){}/* SKILL */}
        class QuestScreen extends Screen{Screen previous;Button status;/* QUEST */}
        class RunScreen extends Screen{Button status,firstStep,training,ready;/* RUN */}
        class DalmaruProfessionScreen extends Screen{DalmaruProfessionScreen(Screen previous){}}
        class DalmaruSkillScreen extends Screen{DalmaruSkillScreen(Screen previous){}}
        class DalmaruStoryScreen extends Screen{DalmaruStoryScreen(Screen previous){}}
        class DalmaruVillageScreen extends Screen{DalmaruVillageScreen(Screen previous){}}
        class DalmaruLifeScreen extends Screen{DalmaruLifeScreen(Screen previous){}}
        class DalmaruLifeSpecScreen extends Screen{DalmaruLifeSpecScreen(Screen previous){}}
        class DalmaruCraftScreen extends Screen{DalmaruCraftScreen(Screen previous){}}
        class DalmaruPrecisionScreen extends Screen{DalmaruPrecisionScreen(Screen previous){}}
        class DalmaruCraftOrderScreen extends Screen{DalmaruCraftOrderScreen(Screen previous){}}
        class DalmaruPayloads{record EquipmentData(UUID equipmentId,int slotId,String itemId,int enhancement,int consecutiveFailures){}}
        class ClientEquipmentState{static List<DalmaruPayloads.EquipmentData>items=List.of();static List<DalmaruPayloads.EquipmentData>equipment(){return items;}}
        class EquipmentScreen extends Screen{List<Button>buttons=new ArrayList<>();List<UUID>displayedIds=List.of();int page,displayedPage=-1;/* EQUIPMENT */}
        class DalmaruInvestigationScreen extends Screen{DalmaruInvestigationScreen(Screen previous){}}
        class ProfileScreen extends Screen{Button statusButton,strengthButton,dexterityButton,arcanaButton,vitalityButton,spiritButton,resetButton,professionButton,skillButton,villageButton,investigationButton;boolean confirmReset;/* PROFILE */}
        class VillageScreen extends Screen{Button statusButton;/* VILLAGE */}
        class CraftScreen extends Screen{Button statusButton;/* CRAFT */}
        class PrecisionScreen extends Screen{Button statusButton;/* PRECISION */}
        class OrderScreen extends Screen{Button statusButton;/* ORDER */}
        class LifeScreen extends Screen{Button statusButton;/* LIFE */}
        class LifeSpecScreen extends Screen{Button statusButton;/* LIFE_SPEC */}
        class HousingScreen extends Screen{Button statusButton;/* HOUSING */}
        class InvestigationScreen extends Screen{Button statusButton;/* INVESTIGATION */}
        class PartyScreen extends Screen{Button status,invite,accept;/* PARTY */}
        public class ScreenHarness{
        static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
        public static void run(){for(int[]size:new int[][]{{320,180},{320,240},{427,240},{640,360},{960,540}})for(Screen screen:List.of(new SkillScreen(),new RunScreen(),new ProfileScreen(),new EquipmentScreen(),new QuestScreen(),new VillageScreen(),new LifeScreen(),new LifeSpecScreen(),new CraftScreen(),new PrecisionScreen(),new OrderScreen(),new HousingScreen(),new InvestigationScreen(),new PartyScreen())){
        screen.width=size[0];screen.height=size[1];screen.init();require(screen.buttons.size()==(screen instanceof SkillScreen?14:screen instanceof RunScreen?6:screen instanceof ProfileScreen?11:screen instanceof VillageScreen?11:screen instanceof LifeScreen?11:screen instanceof LifeSpecScreen?14:screen instanceof CraftScreen?4:screen instanceof PrecisionScreen?4:screen instanceof OrderScreen?5:screen instanceof HousingScreen?8:screen instanceof InvestigationScreen?6:screen instanceof QuestScreen?4:screen instanceof PartyScreen?9:3),"Missing actions");
        for(var b:screen.buttons)require(b.x>=0&&b.y>=0&&b.w>0&&b.h>0&&b.x+b.w<=screen.width&&b.y+b.h<=screen.height,"Clipped action at "+size[0]+"x"+size[1]);
        for(int i=0;i<screen.buttons.size();i++)for(int j=i+1;j<screen.buttons.size();j++){var a=screen.buttons.get(i);var b=screen.buttons.get(j);require(a.x+a.w<=b.x||b.x+b.w<=a.x||a.y+a.h<=b.y||b.y+b.h<=a.y,"Overlapping actions at "+size[0]+"x"+size[1]);}
        for(var box:screen.boxes){require(box.x>=0&&box.y>=0&&box.w>0&&box.h>0&&box.x+box.w<=screen.width&&box.y+box.h<=screen.height,"Clipped field at "+size[0]+"x"+size[1]);for(var b:screen.buttons)require(box.x+box.w<=b.x||b.x+b.w<=box.x||box.y+box.h<=b.y||b.y+b.h<=box.y,"Field overlaps action at "+size[0]+"x"+size[1]);}
        }
        DalmaruClient.partyActions.clear();var party=new PartyScreen();party.width=320;party.height=180;party.init();require(DalmaruClient.partyActions.equals(List.of("party_menu")),"Party open does not request the roster");DalmaruClient.partyActions.clear();party.buttons.get(1).action.accept(party.buttons.get(1));party.buttons.get(2).action.accept(party.buttons.get(2));party.buttons.get(3).action.accept(party.buttons.get(3));party.buttons.get(4).action.accept(party.buttons.get(4));party.buttons.get(5).action.accept(party.buttons.get(5));party.buttons.get(6).action.accept(party.buttons.get(6));party.buttons.get(7).action.accept(party.buttons.get(7));require(DalmaruClient.partyActions.equals(List.of("party_menu","invite","accept","kick_vote","kick_yes","kick_no","leave")),"Party buttons do not call their actions");
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
