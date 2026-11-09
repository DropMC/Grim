package ac.grim.grimac.checks.impl.badpackets;

import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.checks.type.PreViaPacketReceiveListener;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;

@CheckData(name = "BadPacketsA", stableKey = "grim.badpackets.duplicate_slot", description = "Sent duplicate slot id")
public class BadPacketsA extends Check implements PreViaPacketReceiveListener, PacketSendListener {
    private static final Verbose V = Verbose.of("slot={sint}");

    private int lastSlot = -1;

    public BadPacketsA(final GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPreViaPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            final int slot = new WrapperPlayClientHeldItemChange(event).getSlot();

            if (slot == lastSlot && flag(V.write(verbose()).sint(slot)) && shouldModifyPackets()) {
                event.setCancelled(true);
                player.onPacketCancel();
            }

            lastSlot = slot;
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        // A server-side slot change moves the client's selection without a packet back, so the
        // client's next pick of its old slot is a real change, not a duplicate.
        if (event.getPacketType() == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            lastSlot = -1;
        }
    }
}
