package com.pragma.ordenes.application.usecases;

import com.pragma.ordenes.domain.model.Orden;
import com.pragma.ordenes.domain.model.Transaccion;
import com.pragma.ordenes.domain.ports.InventarioServicePort;
import com.pragma.ordenes.domain.ports.OrdenRepositoryPort;
import com.pragma.ordenes.domain.ports.TransaccionRepositoryPort;
import com.pragma.ordenes.infrastructure.exceptions.InventarioServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class CancelarOrdenUseCase {

    private static final Logger logger = LoggerFactory.getLogger(CancelarOrdenUseCase.class);

    private final OrdenRepositoryPort ordenRepository;
    private final InventarioServicePort inventarioService;
    private final TransaccionRepositoryPort transaccionRepository;

    public CancelarOrdenUseCase(
            OrdenRepositoryPort ordenRepository,
            InventarioServicePort inventarioService,
            TransaccionRepositoryPort transaccionRepository) {
        this.ordenRepository = ordenRepository;
        this.inventarioService = inventarioService;
        this.transaccionRepository = transaccionRepository;
    }

    public Orden cancelarOrden(UUID id) {
        logger.info("Iniciando proceso de cancelación de orden: {}", id);

        Orden orden = getOrden(id);
        persistirOrden(orden);
        actualizarInventario(orden);
        registrarTransacciones(orden);

        logger.info("Orden {} cancelada exitosamente para cliente: {}",
                orden.getId(), orden.getClienteId());
        return orden;
    }

    private Orden getOrden(UUID idOrden) {
        logger.debug("Validando orden: {}", idOrden);

        if (idOrden == null) {
            throw new IllegalArgumentException("El ID de la orden es obligatorio");
        }

        return ordenRepository.buscarPorId(idOrden).get();
    }

    private Orden persistirOrden(Orden orden) {
        logger.debug("Persistiendo orden para cliente: {}", orden.getClienteId());

        orden.setFechaActualizacion(LocalDateTime.now());
        orden.setEstado(Orden.EstadoOrden.CANCELADA);

        Orden ordenGuardada = ordenRepository.guardar(orden);

        logger.info("Orden {} persistida con estado: {}",
                ordenGuardada.getId(), ordenGuardada.getEstado());
        return ordenGuardada;
    }

    private void actualizarInventario(Orden orden) {
        logger.debug("Actualizando inventario para orden: {}", orden.getId());

        try {
            inventarioService.decrementarStock(orden.getItems());
            logger.info("Inventario actualizado correctamente para orden: {}", orden.getId());
        } catch (Exception e) {
            logger.error("Error al actualizar inventario para orden: {}. Restaurando estado...",
                    orden.getId(), e);
            inventarioService.restaurarStock(orden.getItems());
            throw new InventarioServiceException(
                    "Error al actualizar el inventario: " + e.getMessage(), e);
        }
    }

    private void registrarTransacciones(Orden orden) {
        logger.debug("Registrando transacciones de auditoría para orden: {}", orden.getId());

        Transaccion transaccionOrden = Transaccion.crearTransaccion(
                "CREACION",
                "ORDEN",
                orden.getId(),
                String.format("Orden creada para cliente %s con %d items, total: %s",
                        orden.getClienteId(),
                        orden.getItems().size(),
                        orden.calcularTotal())
        );

        transaccionRepository.guardar(transaccionOrden);

        for (Orden.ItemOrden item : orden.getItems()) {
            Transaccion transaccionInventario = Transaccion.crearTransaccion(
                    "DECREMENTO_STOCK",
                    "INVENTARIO",
                    item.getProductoId(),
                    String.format("Stock decrementado en %d unidades para orden %s",
                            item.getCantidad(),
                            orden.getId())
            );

            transaccionRepository.guardar(transaccionInventario);
        }

        logger.info("Transacciones de auditoría registradas para orden: {}", orden.getId());
    }
}