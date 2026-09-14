package com.hmdp.ops;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.agent.AgentException;
import com.hmdp.service.*;
import com.hmdp.config.RabbitMQTopicConfig;
import com.rabbitmq.client.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpsSafetyTest {
    OpsProperties p; OpsEvidenceService evidence; OpsActionStore store; MqOperationsService mq; OpsWorkflowService workflow;
    final String id=UUID.randomUUID().toString(), incident=UUID.randomUUID().toString();
    @BeforeEach void setup() {
        p=new OpsProperties();p.setEnabled(true);p.setDiagnosticians(Set.of(7L));p.setOperators(Set.of(7L));
        evidence=mock(OpsEvidenceService.class);store=mock(OpsActionStore.class);mq=mock(MqOperationsService.class);
        workflow=new OpsWorkflowService(p,evidence,store,mq,new OrderMessageService(new ObjectMapper()),new ObjectMapper());
        when(mq.state(false)).thenReturn(new MqOperationsService.QueueState("seckillQueue",true,0,1));
        when(mq.state(true)).thenReturn(new MqOperationsService.QueueState("seckillQueue.dlq",true,2,0));
        when(evidence.databaseHealthy()).thenReturn(true);
        when(store.owned(id,7L,"local")).thenReturn(Map.of("status","PENDING","incident_id",incident,"batch_size",1));
        when(store.claim(id,7L,"local",true)).thenReturn(true);
    }
    @Test void merchantIdIsNotOpsPermission() {
        assertThrows(AgentException.class,()->workflow.diagnose(1010)); verifyNoInteractions(evidence,store,mq);
    }
    @Test void readPermissionDoesNotAllowReplay() {
        p.setOperators(Set.of()); assertThrows(AgentException.class,()->workflow.propose(7,incident,1)); verifyNoInteractions(mq,store);
    }
    @Test void proposalNeverConsumesOrPublishes() {
        var result=workflow.propose(7,incident,2);assertEquals("PENDING",result.get("status"));
        verify(mq,never()).channel();verify(store).propose(anyString(),eq(incident),eq(7L),eq("local"),eq(2));
    }
    @Test void boundedBatchAndExplicitHumanConfirmation() {
        assertThrows(IllegalArgumentException.class,()->workflow.propose(7,incident,6));
        assertThrows(IllegalArgumentException.class,()->workflow.decide(7,id,true,false));verify(mq,never()).channel();
    }
    @Test void revokedPermissionStopsDecision() {
        p.setOperators(Set.of());assertThrows(AgentException.class,()->workflow.decide(7,id,true,true));verify(store,never()).claim(anyString(),anyLong(),anyString(),anyBoolean());
    }
    @Test void missingConsumerBlocksExecution() {
        when(mq.state(false)).thenReturn(new MqOperationsService.QueueState("seckillQueue",true,0,0));
        assertThrows(IllegalArgumentException.class,()->workflow.decide(7,id,true,true));verify(mq,never()).channel();
    }
    @Test void staleOrRepeatedClaimNeverExecutes() {
        when(store.claim(id,7L,"local",true)).thenReturn(false);
        assertThrows(IllegalArgumentException.class,()->workflow.decide(7,id,true,true));verify(mq,never()).channel();
    }
    @Test void confirmationPrecedesAckAndBusinessSuccessIsNotAssumed() throws Exception {
        Channel ch=mock(Channel.class);when(mq.channel()).thenReturn(ch);
        var body="{\"id\":10,\"userId\":7,\"voucherId\":1}".getBytes();
        when(ch.basicGet(RabbitMQTopicConfig.DLQ,false)).thenReturn(new GetResponse(new Envelope(9,false,"",""),new AMQP.BasicProperties(),body,0));
        workflow.decide(7,id,true,true);
        var order=inOrder(store,mq,ch);order.verify(store).item(id,0,10);order.verify(mq).publishConfirmed(eq(RabbitMQTopicConfig.QUEUE),eq(body),anyMap());order.verify(store).sent(id,0);order.verify(ch).basicAck(9,false);
        verify(store).finish(eq(id),eq("PUBLISHED"),anyString(),eq(true));
    }
    @Test void lostPublisherConfirmReturnsSourceAndKeepsGlobalLock() throws Exception {
        Channel ch=mock(Channel.class);when(mq.channel()).thenReturn(ch);
        when(ch.basicGet(RabbitMQTopicConfig.DLQ,false)).thenReturn(new GetResponse(new Envelope(9,false,"",""),new AMQP.BasicProperties(),"{\"id\":10,\"userId\":7,\"voucherId\":1}".getBytes(),0));
        doThrow(new java.io.IOException()).when(mq).publishConfirmed(anyString(),any(),anyMap());
        workflow.decide(7,id,true,true);
        verify(ch,never()).basicAck(anyLong(),anyBoolean());verify(ch).basicNack(9,false,true);
        verify(store).finish(eq(id),eq("UNCERTAIN"),anyString(),eq(false));
    }
    @Test void terminalStateIsNotRetried() {
        when(store.owned(id,7L,"local")).thenReturn(Map.of("status","UNCERTAIN"));
        workflow.decide(7,id,true,true);verify(mq,never()).channel();verify(store,never()).claim(anyString(),anyLong(),anyString(),anyBoolean());
    }
}
