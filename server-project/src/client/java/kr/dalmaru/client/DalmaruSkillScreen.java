package kr.dalmaru.client;

import java.util.*;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.skill.SkillDefinition;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class DalmaruSkillScreen extends Screen {
    private final Screen previous;
    private final List<Button> slotButtons=new ArrayList<>();
    private final List<Button> learnButtons=new ArrayList<>();
    private List<SkillDefinition> skills=List.of();
    DalmaruSkillScreen(Screen previous){super(Component.literal("기술 습득 · 전투 장착"));this.previous=previous;}
    @Override protected void init(){
        slotButtons.clear();learnButtons.clear();var response=ClientProfileState.current();
        if(response==null||response.profile().profession().isBlank())return;
        skills=SkillDefinition.forProfession(CharacterProfession.valueOf(response.profile().profession()));
        int contentWidth=Math.min(340,width-20),left=(width-contentWidth)/2,columnWidth=(contentWidth-10)/2;
        int step=Math.min(25,(height-60)/Math.max(5,skills.size())),buttonHeight=Math.min(20,step-2);
        int top=Math.max(20,(height-40-skills.size()*step)/2);
        for(int index=0;index<skills.size();index++){
            var skill=skills.get(index);learnButtons.add(addRenderableWidget(Button.builder(Component.literal(skill.koreanName()),button->DalmaruClient.requestSkill(skill.id())).bounds(left,top+index*step,columnWidth,buttonHeight).build()));
        }
        for(int index=0;index<5;index++){final int slot=index;slotButtons.add(addRenderableWidget(Button.builder(Component.literal(""),button->cycle(slot)).bounds(left+columnWidth+10,top+index*step,columnWidth,buttonHeight).build()));}
        addRenderableWidget(Button.builder(Component.literal("돌아가기"),button->minecraft.setScreen(previous)).bounds(left,height-25,contentWidth,20).build());
        DalmaruClient.requestSkillRanks();DalmaruClient.requestLoadout();tick();
    }
    private void cycle(int index){
        var current=ClientLoadoutState.current();if(current==null||index==4)return;
        var choices=new ArrayList<String>();choices.add("");for(var skill:skills)if(skill.kind()==SkillDefinition.Kind.ACTIVE&&ClientSkillState.rank(skill.id())>0)choices.add(skill.id());
        var slots=new ArrayList<>(current.slots());String next=choices.get((choices.indexOf(slots.get(index))+1)%choices.size());
        if(!next.isEmpty())for(int other=0;other<4;other++)if(other!=index&&next.equals(slots.get(other)))slots.set(other,"");slots.set(index,next);DalmaruClient.updateLoadout(slots);
    }
    @Override public void tick(){
        for(int i=0;i<learnButtons.size();i++){
            var skill=skills.get(i);int rank=ClientSkillState.rank(skill.id());var button=learnButtons.get(i);
            button.setMessage(Component.literal(skill.koreanName()+" "+rank+"/5 "+(rank>=5?"[최대]":"[+1]")));
            button.active=rank<5&&ClientProfileState.canLearn();
            button.setTooltip(Tooltip.create(Component.literal(skill.description())));
        }
        var current=ClientLoadoutState.current();
        for(int i=0;i<slotButtons.size();i++){
            String id=current==null?"":current.slots().get(i);String name=skills.stream().filter(skill->skill.id().equals(id)).map(SkillDefinition::koreanName).findFirst().orElse("비움");
            boolean unlearned=!id.isEmpty()&&ClientSkillState.rank(id)<1;
            var button=slotButtons.get(i);button.setMessage(Component.literal((i+1)+" · "+(i==4?"궁극기 미해금":unlearned?"미습득 (잠금)":name)));button.active=i<4&&!unlearned&&current!=null&&current.editable()&&!ClientLoadoutState.waiting();
        }
    }
}
