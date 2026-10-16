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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Caso de uso para la creación de órdenes.
 * Coordina la lógica de negocio y la interacción con los puertos de inventario
 * y registro de transacciones. Implementa el patrón de aplicación de casos de uso
 * manteniendo el dominio limpio de dependencias de infraestructura.
 */
@Service
public class CrearOrdenUseCase {

    private static final Logger logger = LoggerFactory.getLogger(CrearOrdenUseCase.class);

    private final OrdenRepositoryPort ordenRepository;
    private final InventarioServicePort inventarioService;
    private final TransaccionRepositoryPort transaccionRepository;

    public CrearOrdenUseCase(
            OrdenRepositoryPort ordenRepository,
            InventarioServicePort inventarioService,
            TransaccionRepositoryPort transaccionRepository) {
        this.ordenRepository = ordenRepository;
        this.inventarioService = inventarioService;
        this.transaccionRepository = transaccionRepository;
    }

    /**
     * Ejecuta el flujo de creación de una orden.
     * Este método orquesta todo el proceso: validación de la orden,
     * verificación de disponibilidad en inventario, persistencia de la orden,
     * decremento de stock y registro de transacciones para auditoría.
     *
     * @param orden La orden a crear
     * @return La orden creada con su ID asignado
     * @throws InventarioServiceException si el inventario no está disponible
     * @throws IllegalArgumentException si la orden no es válida
     */
    public Orden ejecutar(Orden orden) {
        logger.info("Iniciando proceso de creación de orden para cliente: {}", orden.getClienteId());

        validarOrden(orden);
        verificarDisponibilidadInventario(orden);
        Orden ordenGuardada = persistirOrden(orden);
        actualizarInventario(ordenGuardada);
        registrarTransacciones(ordenGuardada);

        logger.info("Orden {} creada exitosamente para cliente: {}",
                ordenGuardada.getId(), ordenGuardada.getClienteId());
        return ordenGuardada;
    }

    private void validarOrden(Orden orden) {
        logger.debug("Validando orden para cliente: {}", orden.getClienteId());

        if (orden.getClienteId() == null || orden.getClienteId().isBlank()) {
            throw new IllegalArgumentException("El ID del cliente es obligatorio");
        }

        if (orden.getItems() == null || orden.getItems().isEmpty()) {
            throw new IllegalArgumentException("La orden debe contener al menos un item");
        }

        orden.validarItems();

        if (orden.calcularTotal().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("El total de la orden debe ser mayor a cero");
        }

        logger.debug("Orden validada correctamente. Total: {}", orden.calcularTotal());
    }

    private void verificarDisponibilidadInventario(Orden orden) {
        logger.debug("Verificando disponibilidad en inventario para {} items", orden.getItems().size());

        boolean disponible = inventarioService.verificarDisponibilidad(orden.getItems());

        if (!disponible) {
            logger.error("No hay disponibilidad en inventario para la orden del cliente: {}",
                    orden.getClienteId());
            throw new InventarioServiceException(
                    "No todos los items solicitados están disponibles en inventario");
        }

        logger.debug("Inventario verificado: todos los items disponibles");
    }

    private Orden persistirOrden(Orden orden) {
        logger.debug("Persistiendo orden para cliente: {}", orden.getClienteId());

        orden.setId(UUID.randomUUID());
        orden.setFechaCreacion(LocalDateTime.now());
        orden.setFechaActualizacion(LocalDateTime.now());
        orden.setEstado(Orden.EstadoOrden.PENDIENTE);

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